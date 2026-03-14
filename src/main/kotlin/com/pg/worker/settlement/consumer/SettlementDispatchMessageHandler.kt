package com.pg.worker.settlement.consumer

import com.fasterxml.jackson.databind.ObjectMapper
import com.gop.logging.contract.LogMdcKeys
import com.gop.logging.contract.LogPrefix
import com.gop.logging.contract.LogResult
import com.gop.logging.contract.LogSuffix
import com.gop.logging.contract.LogType
import com.gop.logging.contract.StepPrefix
import com.gop.logging.contract.StructuredLogger
import com.pg.worker.global.logging.WorkerLogPayloadKeys
import com.pg.worker.settlement.application.usecase.command.RecordSettlementCommandUseCase
import com.pg.worker.settlement.application.usecase.command.dto.RecordSettlementCommand
import com.pg.worker.settlement.application.usecase.command.dto.RecordSettlementResult
import com.pg.worker.settlement.consumer.dto.SettlementDispatchMessage
import com.pg.worker.settlement.consumer.dto.SettlementEventPayload
import org.slf4j.MDC
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.time.format.DateTimeParseException
import java.time.format.DateTimeFormatter

@Component
@LogPrefix(StepPrefix.SETTLEMENT_LEDGER)
class SettlementDispatchMessageHandler(
    private val objectMapper: ObjectMapper,
    private val recordSettlementCommandUseCase: RecordSettlementCommandUseCase,
    private val structuredLogger: StructuredLogger
) {
    @LogSuffix("dispatchMessage")
    fun handle(messageBody: String): SettlementDispatchHandleResult {
        val message = try {
            objectMapper.readValue(messageBody, SettlementDispatchMessage::class.java)
        } catch (e: Exception) {
            structuredLogger.error(
                logType = LogType.INTEGRATION,
                result = LogResult.FAIL,
                payload = mapOf(WorkerLogPayloadKeys.REASON to "invalid_message_envelope"),
                error = e
            )
            return SettlementDispatchHandleResult.NonRetryableFailure("INVALID_ENVELOPE")
        }

        if (message.schemaVersion != 1) {
            structuredLogger.warn(
                logType = LogType.INTEGRATION,
                result = LogResult.SKIP,
                payload = mapOf(
                    WorkerLogPayloadKeys.REASON to "unsupported_schema_version",
                    WorkerLogPayloadKeys.SCHEMA_VERSION to message.schemaVersion,
                    WorkerLogPayloadKeys.EVENT_ID to message.eventId
                )
            )
            return SettlementDispatchHandleResult.NonRetryableFailure("UNSUPPORTED_SCHEMA_VERSION")
        }

        val messageId = message.messageId
        val occurredAt = message.occurredAt
        val eventType = message.eventType

        if (messageId.isBlank() || occurredAt.isBlank() || eventType.isBlank()) {
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
            return SettlementDispatchHandleResult.NonRetryableFailure("MISSING_REQUIRED_FIELDS")
        }

        message.traceId?.let { MDC.put(LogMdcKeys.TRACE_ID, it) }
        MDC.put(LogMdcKeys.MESSAGE_ID, message.messageId)
        MDC.put(LogMdcKeys.EVENT_ID, message.eventId.toString())
        MDC.put(LogMdcKeys.MERCHANT_ID, message.merchantId.toString())

        return try {
            val payload = try {
                objectMapper.readValue(message.payload, SettlementEventPayload::class.java)
            } catch (e: Exception) {
                structuredLogger.error(
                    logType = LogType.INTEGRATION,
                    result = LogResult.FAIL,
                    payload = mapOf(
                        WorkerLogPayloadKeys.REASON to "invalid_payload",
                        WorkerLogPayloadKeys.EVENT_ID to message.eventId
                    ),
                    error = e
                )
                return SettlementDispatchHandleResult.NonRetryableFailure("INVALID_PAYLOAD")
            }

            val transactionType = normalizeTransactionType(payload.transactionType)
                ?: run {
                    structuredLogger.warn(
                        logType = LogType.INTEGRATION,
                        result = LogResult.SKIP,
                        payload = mapOf(
                            WorkerLogPayloadKeys.REASON to "unsupported_transaction_type",
                            WorkerLogPayloadKeys.TRANSACTION_TYPE to payload.transactionType,
                            WorkerLogPayloadKeys.EVENT_ID to message.eventId
                        )
                    )
                    return SettlementDispatchHandleResult.NonRetryableFailure("UNSUPPORTED_TRANSACTION_TYPE")
                }

            val eventOccurredAt = try {
                LocalDateTime.parse(message.occurredAt, DateTimeFormatter.ISO_DATE_TIME)
            } catch (e: DateTimeParseException) {
                structuredLogger.error(
                    logType = LogType.INTEGRATION,
                    result = LogResult.FAIL,
                    payload = mapOf(
                        WorkerLogPayloadKeys.REASON to "invalid_occurred_at",
                        WorkerLogPayloadKeys.EVENT_ID to message.eventId,
                        WorkerLogPayloadKeys.OCCURRED_AT to message.occurredAt
                    ),
                    error = e
                )
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

            when (val result = recordSettlementCommandUseCase.record(command)) {
                is RecordSettlementResult.Success -> {
                    structuredLogger.info(
                        logType = LogType.INTEGRATION,
                        result = LogResult.SUCCESS,
                        payload = mapOf(WorkerLogPayloadKeys.EVENT_ID to message.eventId)
                    )
                    SettlementDispatchHandleResult.Success
                }

                is RecordSettlementResult.SkippedAlreadyProcessed -> {
                    structuredLogger.info(
                        logType = LogType.INTEGRATION,
                        result = LogResult.SKIP,
                        payload = mapOf(
                            WorkerLogPayloadKeys.EVENT_ID to message.eventId,
                            WorkerLogPayloadKeys.REASON to "already_processed"
                        )
                    )
                    SettlementDispatchHandleResult.Success
                }

                is RecordSettlementResult.RetryScheduled -> {
                    structuredLogger.warn(
                        logType = LogType.INTEGRATION,
                        result = LogResult.RETRY,
                        payload = mapOf(
                            WorkerLogPayloadKeys.EVENT_ID to message.eventId,
                            WorkerLogPayloadKeys.REASON to result.reason,
                            WorkerLogPayloadKeys.RETRY_COUNT to result.retryCount,
                            WorkerLogPayloadKeys.NEXT_RETRY_AT to result.nextRetryAt?.toString()
                        )
                    )
                    SettlementDispatchHandleResult.Success
                }

                is RecordSettlementResult.NonRetryableFailed -> {
                    structuredLogger.warn(
                        logType = LogType.INTEGRATION,
                        result = LogResult.FAIL,
                        payload = mapOf(
                            WorkerLogPayloadKeys.EVENT_ID to message.eventId,
                            WorkerLogPayloadKeys.REASON to result.reason
                        )
                    )
                    SettlementDispatchHandleResult.NonRetryableFailure(result.reason)
                }
            }
        } catch (e: Exception) {
            structuredLogger.error(
                logType = LogType.INTEGRATION,
                result = LogResult.RETRY,
                payload = mapOf(
                    WorkerLogPayloadKeys.REASON to "business_processing_failed",
                    WorkerLogPayloadKeys.EVENT_ID to message.eventId
                ),
                error = e
            )
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
