package com.pg.worker.webhook.application.service

import com.pg.worker.webhook.application.EndpointConcurrencyLimiter
import com.pg.worker.webhook.application.service.dto.EndpointKey
import com.pg.worker.webhook.application.usecase.command.SendWebhookDeliveriesUseCase
import com.pg.worker.webhook.application.usecase.dto.ClaimedDelivery
import com.pg.worker.webhook.application.usecase.repository.WebhookDeliveryStateRepository
import com.pg.worker.webhook.application.usecase.repository.WebhookEndpointReadRepository
import com.pg.worker.webhook.application.usecase.repository.WebhookSendClient
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

        val futures = claimed.map { delivery -> CompletableFuture.runAsync({ processSingle(delivery, endpointMap) }, sendExecutor) }
        CompletableFuture.allOf(*futures.toTypedArray()).join()
    }

    @PreDestroy
    fun shutdown() {
        sendExecutor.shutdown()
    }

    private fun processSingle(delivery: ClaimedDelivery, endpointMap: Map<EndpointKey, WebhookEndpoint>) {
        if (!concurrencyLimiter.tryAcquire(delivery.endpointId)) {
            deliveryStateRepository.revertClaim(delivery.deliveryId)
            log.debug(
                "[SendWebhookService] deliveryId={} endpointId={} concurrency limited -> revert claim",
                delivery.deliveryId,
                delivery.endpointId,
            )
            return
        }
        try {
            val endpoint = endpointMap[EndpointKey(delivery.merchantId, delivery.endpointId)]
                ?: return deadBecauseEndpointMissing(delivery)
            sendAndRecord(delivery, endpoint)
        } finally {
            concurrencyLimiter.release(delivery.endpointId)
        }
    }

    private fun sendAndRecord(delivery: ClaimedDelivery, endpoint: WebhookEndpoint) {
        val startMs = System.currentTimeMillis()

        try {
            val result = sendClient.send(
                url = endpoint.url,
                secret = decryptSecret(endpoint.secret),
                eventId = delivery.eventId,
                payloadSnapshot = delivery.payloadSnapshot,
            )
            val responseMs = System.currentTimeMillis() - startMs

            when (RetryClassifier.classifyHttpStatus(result.httpStatus)) {
                RetryClassifier.Outcome.SUCCESS ->
                    success(delivery, result.httpStatus, responseMs)

                RetryClassifier.Outcome.RETRY ->
                    retry(delivery, result.httpStatus, RetryClassifier.toErrorCode(result.httpStatus))

                RetryClassifier.Outcome.DEAD ->
                    dead(delivery, result.httpStatus, RetryClassifier.toErrorCode(result.httpStatus))
            }
        } catch (e: IOException) {
            retry(delivery, httpStatus = null, errorCode = RetryClassifier.toNetworkErrorCode(e))
        } catch (e: Exception) {
            val errorCode = "$ERROR_INTERNAL_PREFIX${e.javaClass.simpleName}"
            log.error("[SendWebhookService] deliveryId={} exception: {}", delivery.deliveryId, errorCode, e)
            retry(delivery, httpStatus = null, errorCode = errorCode)
        }
    }

    private fun success(delivery: ClaimedDelivery, httpStatus: Int, responseMs: Long) {
        deliveryStateRepository.markSuccessNewTransaction(delivery.deliveryId, httpStatus, responseMs)
        metrics.recordDeliverySuccess()
        log.debug(
            "[SendWebhookService] deliveryId={} SUCCESS status={} ms={}",
            delivery.deliveryId,
            httpStatus,
            responseMs,
        )
    }

    private fun retry(delivery: ClaimedDelivery, httpStatus: Int?, errorCode: String) {
        handleRetry(delivery, httpStatus, errorCode)
    }

    private fun dead(delivery: ClaimedDelivery, httpStatus: Int?, errorCode: String) {
        deliveryStateRepository.markDeadNewTransaction(delivery.deliveryId, httpStatus, errorCode)
        metrics.recordDeliveryDead()
        log.warn("[SendWebhookService] deliveryId={} DEAD status={}", delivery.deliveryId, httpStatus)
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

    private fun handleRetry(delivery: ClaimedDelivery, httpStatus: Int?, errorCode: String) {
        if (delivery.attemptNo >= maxAttempts) {
            deliveryStateRepository.markDeadNewTransaction(delivery.deliveryId, httpStatus, "$errorCode:$ERROR_MAX_ATTEMPTS_EXCEEDED")
            metrics.recordDeliveryDead()
            log.warn(
                "[SendWebhookService] deliveryId={} DEAD (attempt={} >= max={})",
                delivery.deliveryId,
                delivery.attemptNo,
                maxAttempts,
            )
        } else {
            val nextAt = BackoffCalculator.nextAttemptAt(delivery.attemptNo)
            deliveryStateRepository.markFailedNewTransaction(delivery.deliveryId, httpStatus, errorCode, nextAt)
            metrics.recordDeliveryRetry()
            log.debug(
                "[SendWebhookService] deliveryId={} FAILED attempt={} nextAt={}",
                delivery.deliveryId,
                delivery.attemptNo,
                nextAt,
            )
        }
    }

    private fun preloadEndpoints(claimed: List<ClaimedDelivery>): Map<EndpointKey, WebhookEndpoint> {
        return claimed.groupBy { it.merchantId }
            .flatMap { (merchantId, deliveries) ->
                val endpointIds = deliveries.map { it.endpointId }.toSet()
                endpointRepository.findByMerchantIdAndEndpointIds(merchantId, endpointIds)
            }.associateBy { EndpointKey(it.merchantId, it.endpointId) }
    }

    private fun deadBecauseEndpointMissing(delivery: ClaimedDelivery) {
        log.warn(
            "[WebhookDeliveryService] deliveryId={} endpointId={} not found → DEAD",
            delivery.deliveryId,
            delivery.endpointId,
        )
        dead(delivery, httpStatus = null, errorCode = ERROR_ENDPOINT_REMOVED)
    }
}
