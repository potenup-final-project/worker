package com.pg.worker.webhook.consumer

import com.fasterxml.jackson.databind.ObjectMapper
import com.pg.worker.webhook.application.usecase.repository.WebhookDeliveryStateRepository
import com.pg.worker.webhook.application.usecase.repository.WebhookEndpointReadRepository
import com.pg.worker.webhook.consumer.dto.WebhookDispatchMessage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class WebhookDispatchMessageHandler(
    private val objectMapper: ObjectMapper,
    private val deliveryRepository: WebhookDeliveryStateRepository,
    private val endpointRepository: WebhookEndpointReadRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun handle(messageBody: String): Boolean {
        val message = try {
            objectMapper.readValue(messageBody, WebhookDispatchMessage::class.java)
        } catch (e: Exception) {
            log.error("[WebhookDispatchMessageHandler] invalid message body", e)
            return true
        }

        val endpointIds = endpointRepository.findActiveEndpointIdsByMerchantId(message.merchantId)
        if (endpointIds.isNotEmpty()) {
            deliveryRepository.bulkInsertIgnore(
                eventId = message.eventId,
                merchantId = message.merchantId,
                endpointIds = endpointIds,
                payloadSnapshot = message.payload,
            )
        }
        return true
    }
}
