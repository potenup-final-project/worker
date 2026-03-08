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
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

@Service
class SendWebhookService(
    private val sendClient: WebhookSendClient,
    private val metrics: WebhookMetrics,
    private val secretEncryptor: SecretEncryptor,
    private val concurrencyLimiter: EndpointConcurrencyLimiter,
    private val endpointRepository: WebhookEndpointReadRepository,
    private val deliveryStateRepository: WebhookDeliveryStateRepository,
    @Value("\${webhook.worker.max-attempts}")
    private val maxAttempts: Int,
    @Value("\${webhook.worker.send-threads}")
    sendThreads: Int,
    @Value("\${webhook.secret.allow-plaintext-fallback}")
    private val allowPlaintextFallback: Boolean,
) : SendWebhookDeliveriesUseCase {
    private val log = LoggerFactory.getLogger(javaClass)
    private val sendExecutor = Executors.newFixedThreadPool(sendThreads)

    companion object {
        private const val ERROR_ENDPOINT_REMOVED = "ENDPOINT_NOT_FOUND:endpoint removed"
        private const val ERROR_INTERNAL_PREFIX = "INTERNAL:"
        private const val ERROR_MAX_ATTEMPTS_EXCEEDED = "MAX_ATTEMPTS_EXCEEDED"
    }

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

        outcomes.forEach { outcome ->
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
        if (!concurrencyLimiter.tryAcquire(delivery.endpointId)) {
            deliveryStateRepository.revertClaim(delivery.deliveryId)
            log.debug(
                "[SendWebhookService] deliveryId={} endpointId={} concurrency limited -> revert claim",
                delivery.deliveryId,
                delivery.endpointId,
            )
            return null
        }

        try {
            val endpoint = endpointMap[EndpointKey(delivery.merchantId, delivery.endpointId)]
                ?: return deadBecauseEndpointMissing(delivery)
            return sendAndClassify(delivery, endpoint)
        } finally {
            concurrencyLimiter.release(delivery.endpointId)
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
        } catch (e: IOException) {
            retry(delivery, httpStatus = null, errorCode = RetryClassifier.toNetworkErrorCode(e))
        } catch (e: Exception) {
            val errorCode = "$ERROR_INTERNAL_PREFIX${e.javaClass.simpleName}"
            log.error("[SendWebhookService] deliveryId={} exception: {}", delivery.deliveryId, errorCode, e)
            retry(delivery, httpStatus = null, errorCode = errorCode)
        }
    }

    private fun success(delivery: ClaimedDelivery, httpStatus: Int, responseMs: Long): DeliverySendOutcome {
        log.debug(
            "[SendWebhookService] deliveryId={} SUCCESS status={} ms={}",
            delivery.deliveryId,
            httpStatus,
            responseMs,
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
        log.warn("[SendWebhookService] deliveryId={} DEAD status={}", delivery.deliveryId, httpStatus)
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
                log.warn("[SendWebhookService] secret decrypt failed - plaintext fallback enabled")
                encryptedSecret
            } else {
                throw e
            }
        }
    }

    private fun handleRetry(delivery: ClaimedDelivery, httpStatus: Int?, errorCode: String): DeliverySendOutcome {
        if (delivery.attemptNo >= maxAttempts) {
            log.warn(
                "[SendWebhookService] deliveryId={} DEAD (attempt={} >= max={})",
                delivery.deliveryId,
                delivery.attemptNo,
                maxAttempts,
            )
            return DeliverySendOutcome(
                deliveryId = delivery.deliveryId,
                status = WebhookDeliveryStatus.DEAD,
                httpStatus = httpStatus,
                errorCode = "$errorCode:$ERROR_MAX_ATTEMPTS_EXCEEDED",
            )
        }

        val nextAt = BackoffCalculator.nextAttemptAt(delivery.attemptNo)
        log.debug(
            "[SendWebhookService] deliveryId={} FAILED attempt={} nextAt={}",
            delivery.deliveryId,
            delivery.attemptNo,
            nextAt,
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
        log.warn(
            "[SendWebhookService] deliveryId={} endpointId={} not found -> DEAD",
            delivery.deliveryId,
            delivery.endpointId,
        )
        return dead(delivery, httpStatus = null, errorCode = ERROR_ENDPOINT_REMOVED)
    }
}
