package com.pg.worker.webhook.infra.messaging.consumer

import com.fasterxml.jackson.databind.ObjectMapper
import com.gop.logging.contract.LogMdcKeys
import com.gop.logging.contract.LogPrefix
import com.gop.logging.contract.LogResult
import com.gop.logging.contract.LogSuffix
import com.gop.logging.contract.LogType
import com.gop.logging.contract.StepPrefix
import com.gop.logging.contract.StructuredLogger
import com.pg.worker.global.logging.WorkerLogPayloadKeys
import com.pg.worker.webhook.application.usecase.repository.WebhookDeliveryStateRepository
import com.pg.worker.webhook.application.usecase.repository.WebhookEndpointReadRepository
import com.pg.worker.webhook.infra.messaging.consumer.dto.WebhookDispatchMessage
import org.slf4j.MDC
import org.springframework.stereotype.Component

@Component
@LogPrefix(StepPrefix.WEBHOOK_DELIVERY)
class WebhookDispatchMessageHandler(
    private val objectMapper: ObjectMapper,
    private val deliveryRepository: WebhookDeliveryStateRepository,
    private val endpointRepository: WebhookEndpointReadRepository,
    private val structuredLogger: StructuredLogger,
) {
    @LogSuffix("dispatchMessage")
    fun handle(messageBody: String): Boolean {
        val message = try {
            objectMapper.readValue(messageBody, WebhookDispatchMessage::class.java)
        } catch (e: Exception) {
            structuredLogger.error(
                logType = LogType.INTEGRATION,
                result = LogResult.FAIL,
                payload = mapOf(WorkerLogPayloadKeys.REASON to "invalid_message_body"),
                error = e
            )
            return true
        }

        if (message.schemaVersion != WebhookDispatchMessage.CURRENT_SCHEMA_VERSION) {
            structuredLogger.warn(
                logType = LogType.INTEGRATION,
                result = LogResult.SKIP,
                payload = mapOf(
                    WorkerLogPayloadKeys.REASON to "unsupported_schema_version",
                    WorkerLogPayloadKeys.SCHEMA_VERSION to message.schemaVersion,
                    WorkerLogPayloadKeys.MESSAGE_ID to message.messageId
                )
            )
            return true
        }

        val messageId = message.messageId
        val occurredAt = message.occurredAt
        val eventType = message.eventType

        if (messageId.isNullOrBlank() || occurredAt.isNullOrBlank() || eventType.isNullOrBlank()) {
            structuredLogger.warn(
                logType = LogType.INTEGRATION,
                result = LogResult.SKIP,
                payload = mapOf(
                    WorkerLogPayloadKeys.REASON to "missing_required_fields",
                    WorkerLogPayloadKeys.SCHEMA_VERSION to message.schemaVersion,
                    WorkerLogPayloadKeys.MESSAGE_ID to messageId,
                    WorkerLogPayloadKeys.OCCURRED_AT to occurredAt,
                    WorkerLogPayloadKeys.EVENT_TYPE to eventType
                )
            )
            return true
        }

        message.traceId?.let { MDC.put(LogMdcKeys.TRACE_ID, it) }
        MDC.put(LogMdcKeys.MESSAGE_ID, messageId)
        MDC.put(LogMdcKeys.EVENT_ID, message.eventId.toString())
        MDC.put(LogMdcKeys.MERCHANT_ID, message.merchantId.toString())

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
            structuredLogger.info(
                logType = LogType.INTEGRATION,
                result = LogResult.SUCCESS,
                payload = mapOf(
                    WorkerLogPayloadKeys.MESSAGE_ID to messageId,
                    WorkerLogPayloadKeys.EVENT_ID to message.eventId,
                    WorkerLogPayloadKeys.MERCHANT_ID to message.merchantId,
                    WorkerLogPayloadKeys.EVENT_TYPE to eventType,
                    WorkerLogPayloadKeys.ENDPOINT_COUNT to endpointIds.size
                )
            )
            true
        } finally {
            MDC.clear()
        }
    }
}
