package com.pg.worker.webhook.consumer

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.pg.worker.webhook.application.usecase.repository.WebhookDeliveryStateRepository
import com.pg.worker.webhook.application.usecase.repository.WebhookEndpointReadRepository
import com.pg.worker.webhook.consumer.dto.WebhookDispatchMessage
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
            eventId = UUID.randomUUID(),
            merchantId = 9L,
            payload = "{\"k\":\"v\"}",
        )
        val json = objectMapper.writeValueAsString(message)

        every { endpointRepository.findActiveEndpointIdsByMerchantId(9L) } returns listOf(11L, 12L)
        every { deliveryRepository.bulkInsertIgnore(any(), any(), any(), any()) } just runs

        val result = handler.handle(json)

        assertTrue(result)
        verify(exactly = 1) {
            deliveryRepository.bulkInsertIgnore(
                eventId = message.eventId,
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
        verify(exactly = 0) { deliveryRepository.bulkInsertIgnore(any(), any(), any(), any()) }
    }
}
