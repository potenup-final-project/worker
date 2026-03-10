package com.pg.worker.webhook.application.service

import com.pg.worker.webhook.application.EndpointConcurrencyLimiter
import com.pg.worker.webhook.application.usecase.dto.ClaimedDelivery
import com.pg.worker.webhook.application.usecase.repository.WebhookDeliveryStateRepository
import com.pg.worker.webhook.application.usecase.repository.WebhookEndpointReadRepository
import com.pg.worker.webhook.application.usecase.repository.WebhookSendClient
import com.pg.worker.webhook.application.usecase.repository.dto.WebhookSendResult
import com.pg.worker.webhook.domain.WebhookDeliveryStatus
import com.pg.worker.webhook.domain.WebhookEndpoint
import com.pg.worker.webhook.util.SecretEncryptor
import com.pg.worker.webhook.util.WebhookMetrics
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.util.UUID

class SendWebhookServiceTest {

    private val sendClient = mockk<WebhookSendClient>()
    private val metrics = mockk<WebhookMetrics>(relaxed = true)
    private val secretEncryptor = mockk<SecretEncryptor>()
    private val limiter = mockk<EndpointConcurrencyLimiter>()
    private val endpointRepository = mockk<WebhookEndpointReadRepository>()
    private val deliveryRepository = mockk<WebhookDeliveryStateRepository>(relaxed = true)

    private fun service(maxAttempts: Int = 2): SendWebhookService {
        return SendWebhookService(
            sendClient = sendClient,
            metrics = metrics,
            secretEncryptor = secretEncryptor,
            concurrencyLimiter = limiter,
            endpointRepository = endpointRepository,
            deliveryStateRepository = deliveryRepository,
            maxAttempts = maxAttempts,
            sendThreads = 1,
            allowPlaintextFallback = false,
        )
    }

    @Test
    fun `성공 응답은 SUCCESS로 반영되고 성공 메트릭이 증가한다`() {
        val delivery = ClaimedDelivery(
            deliveryId = 1L,
            endpointId = 101L,
            messageId = "msg-1",
            traceId = "trace-1",
            eventType = "CHECKOUT_CONFIRMED",
            eventId = UUID.randomUUID(),
            merchantId = 9L,
            payloadSnapshot = "{}",
            attemptNo = 1,
        )
        val endpoint = mockk<WebhookEndpoint>()
        every { endpoint.merchantId } returns 9L
        every { endpoint.endpointId } returns 101L
        every { endpoint.url } returns "https://a.test"
        every { endpoint.secret } returns "enc"

        every { deliveryRepository.claimDueBatch(10) } returns listOf(delivery)
        every { endpointRepository.findByMerchantIdAndEndpointIds(9L, setOf(101L)) } returns listOf(endpoint)
        every { limiter.tryAcquire(101L) } returns true
        every { limiter.release(101L) } just runs
        every { secretEncryptor.decrypt("enc") } returns "plain"
        every { sendClient.send(any(), any(), any(), any()) } returns WebhookSendResult(httpStatus = 200, responseMs = 15)

        val outcomes = slot<List<com.pg.worker.webhook.application.usecase.repository.dto.DeliverySendOutcome>>()
        every { deliveryRepository.applySendOutcomesNewTransaction(capture(outcomes)) } just runs

        service().sendBatch(10)

        assertEquals(1, outcomes.captured.size)
        assertEquals(WebhookDeliveryStatus.SUCCESS, outcomes.captured[0].status)
        verify(exactly = 1) { metrics.recordDeliveryOutcome(WebhookDeliveryStatus.SUCCESS, 101L, null, "CHECKOUT_CONFIRMED") }
        verify(exactly = 1) { metrics.recordDeliverySuccess() }
        verify(exactly = 0) { metrics.recordDeliveryRetry() }
        verify(exactly = 0) { metrics.recordDeliveryDead() }
    }

    @Test
    fun `일시 장애는 FAILED로 반영되어 재시도 대상이 된다`() {
        val delivery = ClaimedDelivery(
            deliveryId = 2L,
            endpointId = 102L,
            messageId = "msg-2",
            traceId = "trace-2",
            eventType = "CHECKOUT_CONFIRMED",
            eventId = UUID.randomUUID(),
            merchantId = 9L,
            payloadSnapshot = "{}",
            attemptNo = 1,
        )
        val endpoint = mockk<WebhookEndpoint>()
        every { endpoint.merchantId } returns 9L
        every { endpoint.endpointId } returns 102L
        every { endpoint.url } returns "https://b.test"
        every { endpoint.secret } returns "enc"

        every { deliveryRepository.claimDueBatch(10) } returns listOf(delivery)
        every { endpointRepository.findByMerchantIdAndEndpointIds(9L, setOf(102L)) } returns listOf(endpoint)
        every { limiter.tryAcquire(102L) } returns true
        every { limiter.release(102L) } just runs
        every { secretEncryptor.decrypt("enc") } returns "plain"
        every { sendClient.send(any(), any(), any(), any()) } returns WebhookSendResult(httpStatus = 500, responseMs = 10)

        val outcomes = slot<List<com.pg.worker.webhook.application.usecase.repository.dto.DeliverySendOutcome>>()
        every { deliveryRepository.applySendOutcomesNewTransaction(capture(outcomes)) } just runs

        service(maxAttempts = 3).sendBatch(10)

        val outcome = outcomes.captured.single()
        assertEquals(WebhookDeliveryStatus.FAILED, outcome.status)
        assertNotNull(outcome.nextAttemptAt)
        verify(exactly = 1) {
            metrics.recordDeliveryOutcome(WebhookDeliveryStatus.FAILED, 102L, "HTTP_500:SERVER_ERROR", "CHECKOUT_CONFIRMED")
        }
        verify(exactly = 1) { metrics.recordDeliveryRetry() }
    }

    @Test
    fun `endpoint가 없으면 DEAD로 반영된다`() {
        val delivery = ClaimedDelivery(
            deliveryId = 3L,
            endpointId = 999L,
            messageId = "msg-3",
            traceId = "trace-3",
            eventType = "CHECKOUT_CONFIRMED",
            eventId = UUID.randomUUID(),
            merchantId = 9L,
            payloadSnapshot = "{}",
            attemptNo = 1,
        )

        every { deliveryRepository.claimDueBatch(10) } returns listOf(delivery)
        every { endpointRepository.findByMerchantIdAndEndpointIds(9L, setOf(999L)) } returns emptyList()
        every { limiter.tryAcquire(999L) } returns true
        every { limiter.release(999L) } just runs

        val outcomes = slot<List<com.pg.worker.webhook.application.usecase.repository.dto.DeliverySendOutcome>>()
        every { deliveryRepository.applySendOutcomesNewTransaction(capture(outcomes)) } just runs

        service().sendBatch(10)

        assertEquals(WebhookDeliveryStatus.DEAD, outcomes.captured.single().status)
        verify(exactly = 1) {
            metrics.recordDeliveryOutcome(
                WebhookDeliveryStatus.DEAD,
                999L,
                "ENDPOINT_NOT_FOUND:endpoint removed",
                "CHECKOUT_CONFIRMED",
            )
        }
        verify(exactly = 1) { metrics.recordDeliveryDead() }
    }

    @Test
    fun `동시성 제한으로 acquire 실패하면 claim을 되돌리고 상태 반영은 하지 않는다`() {
        val delivery = ClaimedDelivery(
            deliveryId = 4L,
            endpointId = 103L,
            messageId = "msg-4",
            traceId = "trace-4",
            eventType = "CHECKOUT_CONFIRMED",
            eventId = UUID.randomUUID(),
            merchantId = 9L,
            payloadSnapshot = "{}",
            attemptNo = 1,
        )

        every { deliveryRepository.claimDueBatch(10) } returns listOf(delivery)
        every { endpointRepository.findByMerchantIdAndEndpointIds(9L, setOf(103L)) } returns emptyList()
        every { limiter.tryAcquire(103L) } returns false
        every { deliveryRepository.revertClaim(4L) } just runs
        every { deliveryRepository.applySendOutcomesNewTransaction(any()) } just runs

        service().sendBatch(10)

        verify(exactly = 1) { deliveryRepository.revertClaim(4L) }
        verify(exactly = 1) { deliveryRepository.applySendOutcomesNewTransaction(emptyList()) }
        verify(exactly = 0) { metrics.recordDeliverySuccess() }
        verify(exactly = 0) { metrics.recordDeliveryRetry() }
        verify(exactly = 0) { metrics.recordDeliveryDead() }
    }
}
