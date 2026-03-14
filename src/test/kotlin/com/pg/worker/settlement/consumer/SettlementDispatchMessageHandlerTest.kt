package com.pg.worker.settlement.consumer

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gop.logging.contract.StructuredLogger
import com.pg.worker.settlement.application.usecase.command.RecordSettlementCommandUseCase
import com.pg.worker.settlement.application.usecase.command.dto.RecordSettlementCommand
import com.pg.worker.settlement.application.usecase.command.dto.RecordSettlementResult
import com.pg.worker.settlement.consumer.dto.SettlementDispatchMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

class SettlementDispatchMessageHandlerTest {

    private val objectMapper = jacksonObjectMapper()
    private val useCase = mockk<RecordSettlementCommandUseCase>()
    private val structuredLogger = mockk<StructuredLogger>(relaxed = true)

    private val handler = SettlementDispatchMessageHandler(
        objectMapper = objectMapper,
        recordSettlementCommandUseCase = useCase,
        structuredLogger = structuredLogger,
    )

    @Test
    fun `유효한 메시지를 처리하면 성공 결과를 반환한다`() {
        val commandSlot = slot<RecordSettlementCommand>()
        every { useCase.record(any()) } returns RecordSettlementResult.Success

        val envelope = SettlementDispatchMessage(
            messageId = "msg-1",
            occurredAt = "2026-03-13T00:00:00",
            eventType = "SETTLEMENT_RECORD",
            eventId = UUID.randomUUID(),
            merchantId = 10L,
            aggregateId = 20L,
            payload = "{\"paymentKey\":\"pay-1\",\"transactionId\":1,\"orderId\":\"ord-1\",\"providerTxId\":\"ptx-1\",\"transactionType\":\"PAYMENT\",\"amount\":1000}",
        )

        val result = handler.handle(objectMapper.writeValueAsString(envelope))

        assertEquals(SettlementDispatchHandleResult.Success, result)
        verify(exactly = 1) { useCase.record(capture(commandSlot)) }
        assertEquals("APPROVE", commandSlot.captured.transactionType)
    }

    @Test
    fun `비정상 envelope는 non-retryable 실패를 반환한다`() {
        val result = handler.handle("not-json")

        assertEquals(
            SettlementDispatchHandleResult.NonRetryableFailure("INVALID_ENVELOPE"),
            result,
        )
        verify(exactly = 0) { useCase.record(any()) }
    }

    @Test
    fun `비즈니스 처리 재시도 예약 결과는 성공 ack를 반환한다`() {
        every { useCase.record(any()) } returns RecordSettlementResult.RetryScheduled(
            reason = "db down",
            retryCount = 1,
            nextRetryAt = null
        )

        val envelope = SettlementDispatchMessage(
            messageId = "msg-2",
            occurredAt = "2026-03-13T00:00:00",
            eventType = "SETTLEMENT_RECORD",
            eventId = UUID.randomUUID(),
            merchantId = 11L,
            aggregateId = 21L,
            payload = "{\"paymentKey\":\"pay-2\",\"transactionId\":2,\"orderId\":\"ord-2\",\"providerTxId\":\"ptx-2\",\"transactionType\":\"CANCEL\",\"amount\":2000}",
        )

        val result = handler.handle(objectMapper.writeValueAsString(envelope))

        assertEquals(
            SettlementDispatchHandleResult.Success,
            result,
        )
        verify(exactly = 1) { useCase.record(any()) }
    }

    @Test
    fun `지원하지 않는 transactionType은 non-retryable 실패를 반환한다`() {
        val envelope = SettlementDispatchMessage(
            messageId = "msg-3",
            occurredAt = "2026-03-13T00:00:00",
            eventType = "SETTLEMENT_RECORD",
            eventId = UUID.randomUUID(),
            merchantId = 12L,
            aggregateId = 22L,
            payload = "{\"paymentKey\":\"pay-3\",\"transactionId\":3,\"orderId\":\"ord-3\",\"providerTxId\":\"ptx-3\",\"transactionType\":\"UNKNOWN\",\"amount\":3000}",
        )

        val result = handler.handle(objectMapper.writeValueAsString(envelope))

        assertEquals(
            SettlementDispatchHandleResult.NonRetryableFailure("UNSUPPORTED_TRANSACTION_TYPE"),
            result,
        )
        verify(exactly = 0) { useCase.record(any()) }
    }

    @Test
    fun `비즈니스 non-retryable 결과는 non-retryable 실패를 반환한다`() {
        every { useCase.record(any()) } returns RecordSettlementResult.NonRetryableFailed("POLICY_NOT_FOUND")

        val envelope = SettlementDispatchMessage(
            messageId = "msg-4",
            occurredAt = "2026-03-13T00:00:00",
            eventType = "SETTLEMENT_RECORD",
            eventId = UUID.randomUUID(),
            merchantId = 13L,
            aggregateId = 23L,
            payload = "{\"paymentKey\":\"pay-4\",\"transactionId\":4,\"orderId\":\"ord-4\",\"providerTxId\":\"ptx-4\",\"transactionType\":\"PAYMENT\",\"amount\":4000}",
        )

        val result = handler.handle(objectMapper.writeValueAsString(envelope))

        assertEquals(
            SettlementDispatchHandleResult.NonRetryableFailure("POLICY_NOT_FOUND"),
            result,
        )
        verify(exactly = 1) { useCase.record(any()) }
    }
}
