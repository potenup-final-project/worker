package com.pg.worker.webhook.application.service

import com.pg.worker.webhook.application.EndpointConcurrencyLimiter
import com.pg.worker.webhook.application.service.dto.EndpointKey
import com.pg.worker.webhook.application.usecase.command.SendWebhookDeliveriesUseCase
import com.pg.worker.webhook.application.usecase.dto.ClaimedDelivery
import com.pg.worker.webhook.application.usecase.repository.WebhookDeliveryStateRepository
import com.pg.worker.webhook.application.usecase.repository.WebhookEndpointReadRepository
import com.pg.worker.webhook.application.usecase.repository.WebhookSendClient
import com.pg.worker.webhook.application.usecase.repository.dto.DeliverySendOutcome
import com.pg.worker.webhook.domain.WebhookDeliveryStatus
import com.pg.worker.webhook.domain.WebhookEndpoint
import com.pg.worker.webhook.util.BackoffCalculator
import com.pg.worker.webhook.util.RetryClassifier
import com.pg.worker.webhook.util.SecretEncryptor
import com.pg.worker.webhook.util.WebhookMetrics
import com.gop.logging.contract.LogMdcKeys
import com.gop.logging.contract.LogPrefix
import com.gop.logging.contract.LogResult
import com.gop.logging.contract.LogSuffix
import com.gop.logging.contract.LogType
import com.gop.logging.contract.ProcessResult
import com.gop.logging.contract.StepPrefix
import com.gop.logging.contract.StructuredLogger
import jakarta.annotation.PreDestroy
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Service
@LogPrefix("send.webhook")
class SendWebhookService(
    private val sendClient: WebhookSendClient,
    private val metrics: WebhookMetrics,
    private val secretEncryptor: SecretEncryptor,
    private val concurrencyLimiter: EndpointConcurrencyLimiter,
    private val endpointRepository: WebhookEndpointReadRepository,
    private val deliveryStateRepository: WebhookDeliveryStateRepository,
    private val structuredLogger: StructuredLogger,
    @Value("\${webhook.worker.max-attempts}")
    private val maxAttempts: Int,
    @Value("\${webhook.worker.send-threads}")
    sendThreads: Int,
    @Value("\${webhook.secret.allow-plaintext-fallback}")
    private val allowPlaintextFallback: Boolean,
) : SendWebhookDeliveriesUseCase {
    private val sendExecutor = Executors.newFixedThreadPool(sendThreads)

    companion object {
        private const val ERROR_ENDPOINT_REMOVED = "ENDPOINT_NOT_FOUND:endpoint removed"
        private const val ERROR_INTERNAL_PREFIX = "INTERNAL:"
        private const val ERROR_MAX_ATTEMPTS_EXCEEDED = "MAX_ATTEMPTS_EXCEEDED"
    }

    @LogSuffix("sendBatch")
    override fun sendBatch(batchSize: Int) {
        val claimed = deliveryStateRepository.claimDueBatch(batchSize)
        if (claimed.isEmpty()) return

        val endpointMap = preloadEndpoints(claimed)

        val futures = claimed.map { delivery ->
            CompletableFuture.supplyAsync({ processSingle(delivery, endpointMap) }, sendExecutor)
        }
        CompletableFuture.allOf(*futures.toTypedArray()).join()

        val outcomes = futures.mapNotNull { it.join() }
        deliveryStateRepository.applySendOutcomesNewTransaction(outcomes)

        val claimedByDeliveryId = claimed.associateBy { it.deliveryId }

        outcomes.forEach { outcome ->
            val claimedDelivery = claimedByDeliveryId[outcome.deliveryId]
            metrics.recordDeliveryOutcome(
                status = outcome.status,
                endpointId = claimedDelivery?.endpointId ?: -1L,
                reason = outcome.errorCode,
                eventType = claimedDelivery?.eventType,
            )
            when (outcome.status) {
                WebhookDeliveryStatus.SUCCESS -> metrics.recordDeliverySuccess()
                WebhookDeliveryStatus.FAILED -> metrics.recordDeliveryRetry()
                WebhookDeliveryStatus.DEAD -> metrics.recordDeliveryDead()
                else -> Unit
            }
        }
    }

    @PreDestroy
    fun shutdown() {
        sendExecutor.shutdown()
        if (!sendExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
            sendExecutor.shutdownNow()
        }
    }

    private fun processSingle(
        delivery: ClaimedDelivery,
        endpointMap: Map<EndpointKey, WebhookEndpoint>,
    ): DeliverySendOutcome? {
        MDC.put(LogMdcKeys.DELIVERY_ID, delivery.deliveryId.toString())
        MDC.put("endpointId", delivery.endpointId.toString())
        MDC.put(LogMdcKeys.EVENT_ID, delivery.eventId.toString())
        MDC.put(LogMdcKeys.MESSAGE_ID, delivery.messageId)
        delivery.traceId?.let { MDC.put(LogMdcKeys.TRACE_ID, it) }

        if (!concurrencyLimiter.tryAcquire(delivery.endpointId)) {
            deliveryStateRepository.revertClaim(delivery.deliveryId)
            structuredLogger.debug(
                logType = LogType.INTEGRATION,
                result = LogResult.SKIP,
                payload = mapOf("reason" to "concurrency_limited", "deliveryId" to delivery.deliveryId, "endpointId" to delivery.endpointId)
            )
            MDC.clear()
            return null
        }

        try {
            val endpoint = endpointMap[EndpointKey(delivery.merchantId, delivery.endpointId)]
                ?: return deadBecauseEndpointMissing(delivery)
            return sendAndClassify(delivery, endpoint)
        } finally {
            concurrencyLimiter.release(delivery.endpointId)
            MDC.clear()
        }
    }

    private fun sendAndClassify(delivery: ClaimedDelivery, endpoint: WebhookEndpoint): DeliverySendOutcome {
        val startMs = System.currentTimeMillis()

        return try {
            val result = sendClient.send(
                url = endpoint.url,
                secret = decryptSecret(endpoint.secret),
                eventId = delivery.eventId,
                payloadSnapshot = delivery.payloadSnapshot,
            )
            val responseMs = System.currentTimeMillis() - startMs
            when (RetryClassifier.classifyHttpStatus(result.httpStatus)) {
                RetryClassifier.Outcome.SUCCESS -> success(delivery, result.httpStatus, responseMs)
                RetryClassifier.Outcome.RETRY -> retry(delivery, result.httpStatus, RetryClassifier.toErrorCode(result.httpStatus))
                RetryClassifier.Outcome.DEAD -> dead(delivery, result.httpStatus, RetryClassifier.toErrorCode(result.httpStatus))
            }
        } catch (e: IllegalArgumentException) {
            dead(delivery, httpStatus = null, errorCode = "ENDPOINT_POLICY_BLOCKED:${e.message}")
        } catch (e: IOException) {
            retry(delivery, httpStatus = null, errorCode = RetryClassifier.toNetworkErrorCode(e))
        } catch (e: Exception) {
            val errorCode = "$ERROR_INTERNAL_PREFIX${e.javaClass.simpleName}"
            structuredLogger.error(
                logType = LogType.INTEGRATION,
                result = LogResult.FAIL,
                payload = mapOf("deliveryId" to delivery.deliveryId, "errorCode" to errorCode),
                error = e
            )
            retry(delivery, httpStatus = null, errorCode = errorCode)
        }
    }

    private fun success(delivery: ClaimedDelivery, httpStatus: Int, responseMs: Long): DeliverySendOutcome {
        structuredLogger.info(
            logType = LogType.INTEGRATION,
            result = LogResult.SUCCESS,
            payload = mapOf("deliveryId" to delivery.deliveryId, "httpStatus" to httpStatus, "durationMs" to responseMs)
        )
        return DeliverySendOutcome(
            deliveryId = delivery.deliveryId,
            status = WebhookDeliveryStatus.SUCCESS,
            httpStatus = httpStatus,
            responseMs = responseMs,
        )
    }

    private fun retry(delivery: ClaimedDelivery, httpStatus: Int?, errorCode: String): DeliverySendOutcome {
        return handleRetry(delivery, httpStatus, errorCode)
    }

    private fun dead(delivery: ClaimedDelivery, httpStatus: Int?, errorCode: String): DeliverySendOutcome {
        structuredLogger.warn(
            logType = LogType.INTEGRATION,
            result = LogResult.FAIL,
            payload = mapOf("deliveryId" to delivery.deliveryId, "httpStatus" to httpStatus, "errorCode" to errorCode, "processResult" to ProcessResult.DLQ.name)
        )
        return DeliverySendOutcome(
            deliveryId = delivery.deliveryId,
            status = WebhookDeliveryStatus.DEAD,
            httpStatus = httpStatus,
            errorCode = errorCode,
        )
    }

    private fun decryptSecret(encryptedSecret: String): String {
        return try {
            secretEncryptor.decrypt(encryptedSecret)
        } catch (e: Exception) {
            if (allowPlaintextFallback) {
                structuredLogger.warn(
                    logType = LogType.SECURITY,
                    result = LogResult.SKIP,
                    payload = mapOf("reason" to "secret_decrypt_failed_plaintext_fallback")
                )
                encryptedSecret
            } else {
                throw e
            }
        }
    }

    private fun handleRetry(delivery: ClaimedDelivery, httpStatus: Int?, errorCode: String): DeliverySendOutcome {
        if (delivery.attemptNo >= maxAttempts) {
            structuredLogger.warn(
                logType = LogType.INTEGRATION,
                result = LogResult.FAIL,
                payload = mapOf("deliveryId" to delivery.deliveryId, "attemptNo" to delivery.attemptNo, "maxAttempts" to maxAttempts, "processResult" to ProcessResult.DLQ.name)
            )
            return DeliverySendOutcome(
                deliveryId = delivery.deliveryId,
                status = WebhookDeliveryStatus.DEAD,
                httpStatus = httpStatus,
                errorCode = "$errorCode:$ERROR_MAX_ATTEMPTS_EXCEEDED",
            )
        }

        val nextAt = BackoffCalculator.nextAttemptAt(delivery.attemptNo)
        structuredLogger.debug(
            logType = LogType.INTEGRATION,
            result = LogResult.RETRY,
            payload = mapOf("deliveryId" to delivery.deliveryId, "attemptNo" to delivery.attemptNo, "nextAttemptAt" to nextAt.toString())
        )
        return DeliverySendOutcome(
            deliveryId = delivery.deliveryId,
            status = WebhookDeliveryStatus.FAILED,
            httpStatus = httpStatus,
            errorCode = errorCode,
            nextAttemptAt = nextAt,
        )
    }

    private fun preloadEndpoints(claimed: List<ClaimedDelivery>): Map<EndpointKey, WebhookEndpoint> {
        return claimed.groupBy { it.merchantId }
            .flatMap { (merchantId, deliveries) ->
                val endpointIds = deliveries.map { it.endpointId }.toSet()
                endpointRepository.findByMerchantIdAndEndpointIds(merchantId, endpointIds)
            }
            .associateBy { EndpointKey(it.merchantId, it.endpointId) }
    }

    private fun deadBecauseEndpointMissing(delivery: ClaimedDelivery): DeliverySendOutcome {
        structuredLogger.warn(
            logType = LogType.INTEGRATION,
            result = LogResult.FAIL,
            payload = mapOf("deliveryId" to delivery.deliveryId, "endpointId" to delivery.endpointId, "reason" to "endpoint_not_found")
        )
        return dead(delivery, httpStatus = null, errorCode = ERROR_ENDPOINT_REMOVED)
    }
}
