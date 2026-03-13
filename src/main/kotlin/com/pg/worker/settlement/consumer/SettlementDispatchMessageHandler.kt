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
import java.time.format.DateTimeFormatter

@Component
class SettlementDispatchMessageHandler(
    private val objectMapper: ObjectMapper,
    private val recordSettlementCommandUseCase: RecordSettlementCommandUseCase
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun handle(messageBody: String): Boolean {
        val message = try {
            objectMapper.readValue(messageBody, SettlementDispatchMessage::class.java)
        } catch (e: Exception) {
            log.error("[PaymentDispatchMessageHandler] invalid message envelope format", e)
            return true
        }

        if (message.schemaVersion != 1) {
            log.warn("[PaymentDispatchMessageHandler] unsupported schemaVersion={} eventId={}", message.schemaVersion, message.eventId)
            return true
        }

        val messageId = message.messageId
        val occurredAt = message.occurredAt
        val eventType = message.eventType

        if (messageId.isBlank() || occurredAt.isBlank() || eventType.isBlank()) {
            log.warn(
                "[PaymentDispatchMessageHandler] missing required fields schemaVersion={} messageId={} occurredAt={} eventType={}",
                message.schemaVersion,
                messageId,
                occurredAt,
                eventType,
            )
            return true
        }

        message.traceId?.let { MDC.put("traceId", it) }
        MDC.put("messageId", message.messageId)
        MDC.put("eventId", message.eventId.toString())
        MDC.put("merchantId", message.merchantId.toString())

        return try {
            val payload = try {
                objectMapper.readValue(message.payload, SettlementEventPayload::class.java)
            } catch (e: Exception) {
                log.error("[PaymentDispatchMessageHandler] invalid payload JSON. eventId={}", message.eventId, e)
                return true
            }

            val command = RecordSettlementCommand(
                eventId = message.eventId.toString(),
                paymentKey = payload.paymentKey,
                transactionId = payload.transactionId,
                orderId = payload.orderId,
                providerTxId = payload.providerTxId,
                merchantId = message.merchantId,
                transactionType = payload.transactionType,
                amount = payload.amount,
                eventOccurredAt = LocalDateTime.parse(message.occurredAt, DateTimeFormatter.ISO_DATE_TIME)
            )
            
            recordSettlementCommandUseCase.record(command)
            log.info("[PaymentDispatchMessageHandler] message processed successfully. eventId={}", message.eventId)
            true
        } catch (e: Exception) {
            log.error("[PaymentDispatchMessageHandler] business processing failure. eventId={}", message.eventId, e)
            false
        } finally {
            MDC.clear()
        }
    }
}
