package com.pg.worker.webhook.consumer

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.pg.worker.webhook.application.usecase.repository.WebhookDeliveryStateRepository
import com.pg.worker.webhook.application.usecase.repository.WebhookEndpointReadRepository
import com.pg.worker.webhook.infra.messaging.consumer.WebhookDispatchMessageHandler
import com.pg.worker.webhook.infra.messaging.consumer.dto.WebhookDispatchMessage
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class WebhookDispatchMessageHandlerTest {

    private val objectMapper = jacksonObjectMapper()
    private val deliveryRepository = mockk<WebhookDeliveryStateRepository>(relaxed = true)
    private val endpointRepository = mockk<WebhookEndpointReadRepository>()

    private val handler = WebhookDispatchMessageHandler(
        objectMapper = objectMapper,
        deliveryRepository = deliveryRepository,
        endpointRepository = endpointRepository,
    )

    @Test
    fun `유효한 메시지를 처리하면 endpoint 기반으로 delivery를 생성한다`() {
        val message = WebhookDispatchMessage(
            messageId = "msg-1",
            occurredAt = "2026-03-08T00:00:00",
            eventType = "CHECKOUT_CONFIRMED",
            eventId = UUID.randomUUID(),
            merchantId = 9L,
            payload = "{\"k\":\"v\"}",
        )
        val json = objectMapper.writeValueAsString(message)

        every { endpointRepository.findActiveEndpointIdsByMerchantId(9L) } returns listOf(11L, 12L)
        every { deliveryRepository.bulkInsertIgnore(any(), any(), any(), any(), any(), any(), any()) } just runs

        val result = handler.handle(json)

        assertTrue(result)
        verify(exactly = 1) {
            deliveryRepository.bulkInsertIgnore(
                eventId = message.eventId,
                messageId = "msg-1",
                traceId = null,
                eventType = "CHECKOUT_CONFIRMED",
                merchantId = 9L,
                endpointIds = listOf(11L, 12L),
                payloadSnapshot = "{\"k\":\"v\"}",
            )
        }
    }

    @Test
    fun `파싱 불가능한 메시지는 에러로 소비 처리하고 후속 작업은 수행하지 않는다`() {
        val result = handler.handle("not-json")

        assertTrue(result)
        verify(exactly = 0) { endpointRepository.findActiveEndpointIdsByMerchantId(any()) }
        verify(exactly = 0) { deliveryRepository.bulkInsertIgnore(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `지원하지 않는 schemaVersion 메시지는 건너뛴다`() {
        val message = WebhookDispatchMessage(
            schemaVersion = WebhookDispatchMessage.CURRENT_SCHEMA_VERSION + 1,
            eventId = UUID.randomUUID(),
            merchantId = 9L,
            payload = "{}",
        )
        val json = objectMapper.writeValueAsString(message)

        val result = handler.handle(json)

        assertTrue(result)
        verify(exactly = 0) { endpointRepository.findActiveEndpointIdsByMerchantId(any()) }
        verify(exactly = 0) { deliveryRepository.bulkInsertIgnore(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `schemaVersion 1에서 필수 필드가 누락되면 건너뛴다`() {
        val message = WebhookDispatchMessage(
            messageId = null,
            occurredAt = null,
            eventType = null,
            eventId = UUID.randomUUID(),
            merchantId = 9L,
            payload = "{}",
        )
        val json = objectMapper.writeValueAsString(message)

        val result = handler.handle(json)

        assertTrue(result)
        verify(exactly = 0) { endpointRepository.findActiveEndpointIdsByMerchantId(any()) }
        verify(exactly = 0) { deliveryRepository.bulkInsertIgnore(any(), any(), any(), any(), any(), any(), any()) }
}
}
