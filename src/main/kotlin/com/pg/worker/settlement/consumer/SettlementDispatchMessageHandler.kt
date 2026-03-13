package com.pg.worker.settlement.consumer

import com.fasterxml.jackson.databind.ObjectMapper
import com.pg.worker.settlement.application.usecase.command.RecordSettlementCommandUseCase
import com.pg.worker.settlement.application.usecase.command.dto.RecordSettlementCommand
import com.pg.worker.settlement.consumer.dto.SettlementDispatchMessage
import com.pg.worker.settlement.consumer.dto.SettlementEventPayload
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.time.format.DateTimeParseException
import java.time.format.DateTimeFormatter

@Component
class SettlementDispatchMessageHandler(
    private val objectMapper: ObjectMapper,
    private val recordSettlementCommandUseCase: RecordSettlementCommandUseCase
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun handle(messageBody: String): SettlementDispatchHandleResult {
        val message = try {
            objectMapper.readValue(messageBody, SettlementDispatchMessage::class.java)
        } catch (e: Exception) {
            log.error("[SettlementDispatchMessageHandler] invalid message envelope format", e)
            return SettlementDispatchHandleResult.NonRetryableFailure("INVALID_ENVELOPE")
        }

        if (message.schemaVersion != 1) {
            log.warn("[SettlementDispatchMessageHandler] unsupported schemaVersion={} eventId={}", message.schemaVersion, message.eventId)
            return SettlementDispatchHandleResult.NonRetryableFailure("UNSUPPORTED_SCHEMA_VERSION")
        }

        val messageId = message.messageId
        val occurredAt = message.occurredAt
        val eventType = message.eventType

        if (messageId.isBlank() || occurredAt.isBlank() || eventType.isBlank()) {
            log.warn(
                "[SettlementDispatchMessageHandler] missing required fields schemaVersion={} messageId={} occurredAt={} eventType={}",
                message.schemaVersion,
                messageId,
                occurredAt,
                eventType,
            )
            return SettlementDispatchHandleResult.NonRetryableFailure("MISSING_REQUIRED_FIELDS")
        }

        message.traceId?.let { MDC.put("traceId", it) }
        MDC.put("messageId", message.messageId)
        MDC.put("eventId", message.eventId.toString())
        MDC.put("merchantId", message.merchantId.toString())

        return try {
            val payload = try {
                objectMapper.readValue(message.payload, SettlementEventPayload::class.java)
            } catch (e: Exception) {
                log.error("[SettlementDispatchMessageHandler] invalid payload JSON. eventId={}", message.eventId, e)
                return SettlementDispatchHandleResult.NonRetryableFailure("INVALID_PAYLOAD")
            }

            val transactionType = normalizeTransactionType(payload.transactionType)
                ?: run {
                    log.warn(
                        "[SettlementDispatchMessageHandler] unsupported transactionType={} eventId={}",
                        payload.transactionType,
                        message.eventId,
                    )
                    return SettlementDispatchHandleResult.NonRetryableFailure("UNSUPPORTED_TRANSACTION_TYPE")
                }

            val eventOccurredAt = try {
                LocalDateTime.parse(message.occurredAt, DateTimeFormatter.ISO_DATE_TIME)
            } catch (e: DateTimeParseException) {
                log.error("[SettlementDispatchMessageHandler] invalid occurredAt format. eventId={}", message.eventId, e)
                return SettlementDispatchHandleResult.NonRetryableFailure("INVALID_OCCURRED_AT")
            }

            val command = RecordSettlementCommand(
                eventId = message.eventId.toString(),
                paymentKey = payload.paymentKey,
                transactionId = payload.transactionId,
                orderId = payload.orderId,
                providerTxId = payload.providerTxId,
                merchantId = message.merchantId,
                transactionType = transactionType,
                amount = payload.amount,
                eventOccurredAt = eventOccurredAt,
            )

            recordSettlementCommandUseCase.record(command)
            log.info("[SettlementDispatchMessageHandler] message accepted. eventId={}", message.eventId)
            SettlementDispatchHandleResult.Success
        } catch (e: Exception) {
            log.error("[SettlementDispatchMessageHandler] business processing failure. eventId={}", message.eventId, e)
            SettlementDispatchHandleResult.RetryableFailure("BUSINESS_PROCESSING_FAILED")
        } finally {
            MDC.clear()
        }
    }

    private fun normalizeTransactionType(rawType: String): String? {
        return when (rawType.trim().uppercase()) {
            "APPROVE", "PAYMENT" -> "APPROVE"
            "CANCEL" -> "CANCEL"
            else -> null
        }
    }
}

sealed interface SettlementDispatchHandleResult {
    data object Success : SettlementDispatchHandleResult
    data class RetryableFailure(val reason: String) : SettlementDispatchHandleResult
    data class NonRetryableFailure(val reason: String) : SettlementDispatchHandleResult
}
