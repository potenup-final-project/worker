package com.pg.worker.webhook.consumer

import com.fasterxml.jackson.databind.ObjectMapper
import com.pg.worker.webhook.application.usecase.repository.WebhookDeliveryStateRepository
import com.pg.worker.webhook.application.usecase.repository.WebhookEndpointReadRepository
import com.pg.worker.webhook.consumer.dto.WebhookDispatchMessage
import org.slf4j.LoggerFactory
import org.slf4j.MDC
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

        if (message.schemaVersion != 1) {
            log.warn("[WebhookDispatchMessageHandler] unsupported schemaVersion={} messageId={}", message.schemaVersion, message.messageId)
            return true
        }

        val messageId = message.messageId
        val occurredAt = message.occurredAt
        val eventType = message.eventType

        if (messageId.isNullOrBlank() || occurredAt.isNullOrBlank() || eventType.isNullOrBlank()) {
            log.warn(
                "[WebhookDispatchMessageHandler] missing required fields schemaVersion={} messageId={} occurredAt={} eventType={}",
                message.schemaVersion,
                messageId,
                occurredAt,
                eventType,
            )
            return true
        }

        message.traceId?.let { MDC.put("traceId", it) }
        MDC.put("messageId", messageId)
        MDC.put("eventId", message.eventId.toString())
        MDC.put("merchantId", message.merchantId.toString())

        return try {
            val endpointIds = endpointRepository.findActiveEndpointIdsByMerchantId(message.merchantId)
            if (endpointIds.isNotEmpty()) {
                deliveryRepository.bulkInsertIgnore(
                    eventId = message.eventId,
                    messageId = messageId,
                    traceId = message.traceId,
                    eventType = eventType,
                    merchantId = message.merchantId,
                    endpointIds = endpointIds,
                    payloadSnapshot = message.payload,
                )
            }
            log.debug(
                "[WebhookDispatchMessageHandler] messageId={} eventId={} merchantId={} eventType={} endpoints={}",
                messageId,
                message.eventId,
                message.merchantId,
                eventType,
                endpointIds.size,
            )
            true
        } finally {
            MDC.clear()
        }
    }
}
