package com.pg.worker.settlement.consumer

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import com.gop.logging.contract.StructuredLogger
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest
import software.amazon.awssdk.services.sqs.model.DeleteMessageResponse
import software.amazon.awssdk.services.sqs.model.Message
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse
import java.util.function.Consumer

class SettlementDispatchSqsConsumerTest {

    private val sqsClient = mockk<SqsClient>()
    private val handler = mockk<SettlementDispatchMessageHandler>()
    private val dlqPublisher = mockk<SettlementDispatchDlqPublisher>()
    private val structuredLogger = mockk<StructuredLogger>(relaxed = true)
    private val queueUrl = "https://sqs.ap-northeast-2.amazonaws.com/111111111111/main-queue"

    private val consumer = SettlementDispatchSqsConsumer(
        sqsClient = sqsClient,
        handler = handler,
        dlqPublisher = dlqPublisher,
        structuredLogger = structuredLogger,
        queueUrl = queueUrl,
        maxMessages = 1,
        waitTimeSeconds = 0,
        visibilityTimeoutSeconds = 30,
    )

    @Test
    fun `성공 처리 시 원본 메시지를 삭제한다`() {
        stubReceiveSingleMessage("msg-body", "receipt-1")
        every { handler.handle("msg-body") } returns SettlementDispatchHandleResult.Success
        every { sqsClient.deleteMessage(any<Consumer<DeleteMessageRequest.Builder>>()) } returns DeleteMessageResponse.builder().build()

        consumer.poll()

        verify(exactly = 1) { handler.handle("msg-body") }
        verify(exactly = 1) { sqsClient.deleteMessage(any<Consumer<DeleteMessageRequest.Builder>>()) }
        verify(exactly = 0) { dlqPublisher.publish(any(), any(), any()) }
    }

    @Test
    fun `non-retryable 실패는 DLQ 전송 성공 시 삭제한다`() {
        stubReceiveSingleMessage("bad-body", "receipt-2")
        every { handler.handle("bad-body") } returns SettlementDispatchHandleResult.NonRetryableFailure("INVALID_PAYLOAD")
        every { dlqPublisher.publish(queueUrl, "bad-body", "INVALID_PAYLOAD") } returns true
        every { sqsClient.deleteMessage(any<Consumer<DeleteMessageRequest.Builder>>()) } returns DeleteMessageResponse.builder().build()

        consumer.poll()

        verify(exactly = 1) { dlqPublisher.publish(queueUrl, "bad-body", "INVALID_PAYLOAD") }
        verify(exactly = 1) { sqsClient.deleteMessage(any<Consumer<DeleteMessageRequest.Builder>>()) }
    }

    @Test
    fun `non-retryable 실패에서 DLQ 전송 실패 시 삭제하지 않는다`() {
        stubReceiveSingleMessage("bad-body", "receipt-3")
        every { handler.handle("bad-body") } returns SettlementDispatchHandleResult.NonRetryableFailure("INVALID_ENVELOPE")
        every { dlqPublisher.publish(queueUrl, "bad-body", "INVALID_ENVELOPE") } returns false

        consumer.poll()

        verify(exactly = 1) { dlqPublisher.publish(queueUrl, "bad-body", "INVALID_ENVELOPE") }
        verify(exactly = 0) { sqsClient.deleteMessage(any<Consumer<DeleteMessageRequest.Builder>>()) }
    }

    @Test
    fun `retryable 실패는 DLQ로 넘기고 원본 메시지를 삭제한다`() {
        stubReceiveSingleMessage("retry-body", "receipt-4")
        every { handler.handle("retry-body") } returns SettlementDispatchHandleResult.RetryableFailure("BUSINESS_PROCESSING_FAILED")
        every { dlqPublisher.publish(queueUrl, "retry-body", "BUSINESS_PROCESSING_FAILED") } returns true
        every { sqsClient.deleteMessage(any<Consumer<DeleteMessageRequest.Builder>>()) } returns DeleteMessageResponse.builder().build()

        consumer.poll()

        verify(exactly = 1) { handler.handle("retry-body") }
        verify(exactly = 1) { dlqPublisher.publish(queueUrl, "retry-body", "BUSINESS_PROCESSING_FAILED") }
        verify(exactly = 1) { sqsClient.deleteMessage(any<Consumer<DeleteMessageRequest.Builder>>()) }
    }

    private fun stubReceiveSingleMessage(body: String, receiptHandle: String) {
        val message = Message.builder().body(body).receiptHandle(receiptHandle).build()
        every { sqsClient.receiveMessage(any<Consumer<ReceiveMessageRequest.Builder>>()) } returns
            ReceiveMessageResponse.builder().messages(listOf(message)).build()
    }
}
