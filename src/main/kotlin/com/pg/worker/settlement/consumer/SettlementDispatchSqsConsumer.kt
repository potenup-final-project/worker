package com.pg.worker.settlement.consumer

import com.gop.logging.contract.LogMdcKeys
import com.gop.logging.contract.LogPrefix
import com.gop.logging.contract.LogResult
import com.gop.logging.contract.LogSuffix
import com.gop.logging.contract.LogType
import com.gop.logging.contract.StepPrefix
import com.gop.logging.contract.StructuredLogger
import com.pg.worker.global.logging.WorkerLogPayloadKeys
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName

@Component
@ConditionalOnProperty(prefix = "settlement.sqs", name = ["enabled"], havingValue = "true")
@LogPrefix(StepPrefix.SETTLEMENT_LEDGER)
class SettlementDispatchSqsConsumer(
    private val sqsClient: SqsClient,
    private val handler: SettlementDispatchMessageHandler,
    private val dlqPublisher: SettlementDispatchDlqPublisher,
    private val structuredLogger: StructuredLogger,
    @Value("\${settlement.sqs.queue-url}") private val queueUrl: String,
    @Value("\${settlement.sqs.max-messages}") private val maxMessages: Int,
    @Value("\${settlement.sqs.wait-time-seconds}") private val waitTimeSeconds: Int,
    @Value("\${settlement.sqs.visibility-timeout-seconds}") private val visibilityTimeoutSeconds: Int,
) {
    init {
        require(queueUrl.isNotBlank()) { "settlement.sqs.queue-url must be configured when settlement.sqs.enabled=true" }
        require(maxMessages in 1..10) { "settlement.sqs.max-messages must be between 1 and 10" }
        require(waitTimeSeconds in 0..20) { "settlement.sqs.wait-time-seconds must be between 0 and 20" }
        require(visibilityTimeoutSeconds >= 10) {
            "settlement.sqs.visibility-timeout-seconds must be at least 10 seconds"
        }
        structuredLogger.info(
            logType = LogType.TECHNICAL,
            result = LogResult.SUCCESS,
            payload = mapOf(
                WorkerLogPayloadKeys.PHASE to "init",
                WorkerLogPayloadKeys.QUEUE_URL to queueUrl.substringAfterLast("/"),
                WorkerLogPayloadKeys.POLL_MAX to maxMessages,
                WorkerLogPayloadKeys.WAIT_SEC to waitTimeSeconds,
                WorkerLogPayloadKeys.VISIBILITY_SEC to visibilityTimeoutSeconds
            )
        )
    }

    @Scheduled(fixedDelayString = "\${settlement.sqs.poll-interval-ms}", scheduler = "settlementSqsPollingScheduler")
    @LogSuffix("poll")
    fun poll() {
        val messages = sqsClient.receiveMessage { req ->
            req.queueUrl(queueUrl)
                .maxNumberOfMessages(maxMessages)
                .waitTimeSeconds(waitTimeSeconds)
                .visibilityTimeout(visibilityTimeoutSeconds)
                .messageSystemAttributeNames(
                    MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT,
                    MessageSystemAttributeName.SENT_TIMESTAMP,
                )
        }.messages()

        if (messages.isNotEmpty()) {
            structuredLogger.info(
                logType = LogType.INTEGRATION,
                result = LogResult.SUCCESS,
                payload = mapOf(
                    WorkerLogPayloadKeys.PHASE to "receive",
                    WorkerLogPayloadKeys.QUEUE_URL to queueUrl.substringAfterLast("/"),
                    WorkerLogPayloadKeys.RECEIVED_COUNT to messages.size
                )
            )
        }

        messages.forEach { message ->
            val receiveCount = message.attributesAsStrings()[MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT.toString()]
            MDC.put(LogMdcKeys.MESSAGE_ID, message.messageId())
            structuredLogger.info(
                logType = LogType.INTEGRATION,
                result = LogResult.START,
                payload = mapOf(
                    WorkerLogPayloadKeys.PHASE to "handle_start",
                    WorkerLogPayloadKeys.SQS_MESSAGE_ID to message.messageId(),
                    WorkerLogPayloadKeys.RECEIVE_COUNT to receiveCount,
                    WorkerLogPayloadKeys.EVENT_BODY_SIZE to message.body().length
                )
            )
            when (val result = runCatching { handler.handle(message.body()) }
                .onFailure { e ->
                    structuredLogger.error(
                        logType = LogType.INTEGRATION,
                        result = LogResult.FAIL,
                        payload = mapOf(
                            WorkerLogPayloadKeys.PHASE to "handler_execution",
                            WorkerLogPayloadKeys.SQS_MESSAGE_ID to message.messageId()
                        ),
                        error = e
                    )
                }
                .getOrElse { SettlementDispatchHandleResult.RetryableFailure("HANDLER_EXCEPTION") }) {
                is SettlementDispatchHandleResult.Success -> {
                    deleteMessage(message.receiptHandle(), message.messageId())
                }

                is SettlementDispatchHandleResult.NonRetryableFailure -> {
                    structuredLogger.warn(
                        logType = LogType.INTEGRATION,
                        result = LogResult.FAIL,
                        payload = mapOf(
                            WorkerLogPayloadKeys.PHASE to "route_dlq",
                            WorkerLogPayloadKeys.REASON to result.reason,
                            WorkerLogPayloadKeys.SQS_MESSAGE_ID to message.messageId()
                        )
                    )
                    routeToDlqAndDeleteIfSucceeded(
                        messageBody = message.body(),
                        receiptHandle = message.receiptHandle(),
                        sqsMessageId = message.messageId(),
                        reason = result.reason
                    )
                }

                is SettlementDispatchHandleResult.RetryableFailure -> {
                    structuredLogger.warn(
                        logType = LogType.INTEGRATION,
                        result = LogResult.RETRY,
                        payload = mapOf(
                            WorkerLogPayloadKeys.PHASE to "route_dlq",
                            WorkerLogPayloadKeys.REASON to result.reason,
                            WorkerLogPayloadKeys.SQS_MESSAGE_ID to message.messageId()
                        )
                    )
                    routeToDlqAndDeleteIfSucceeded(
                        messageBody = message.body(),
                        receiptHandle = message.receiptHandle(),
                        sqsMessageId = message.messageId(),
                        reason = result.reason
                    )
                }
            }
            MDC.remove(LogMdcKeys.MESSAGE_ID)
        }
    }

    private fun routeToDlqAndDeleteIfSucceeded(
        messageBody: String,
        receiptHandle: String,
        sqsMessageId: String,
        reason: String
    ) {
        val dlqSent = dlqPublisher.publish(
            originalQueueUrl = queueUrl,
            messageBody = messageBody,
            failureReason = reason,
        )
        if (dlqSent) {
            deleteMessage(receiptHandle, sqsMessageId)
        } else {
            structuredLogger.error(
                logType = LogType.INTEGRATION,
                result = LogResult.FAIL,
                payload = mapOf(
                    WorkerLogPayloadKeys.PHASE to "route_dlq",
                    WorkerLogPayloadKeys.REASON to reason,
                    WorkerLogPayloadKeys.QUEUE_URL to queueUrl.substringAfterLast("/"),
                    WorkerLogPayloadKeys.SQS_MESSAGE_ID to sqsMessageId
                )
            )
        }
    }

    private fun deleteMessage(receiptHandle: String, sqsMessageId: String) {
        runCatching {
            sqsClient.deleteMessage { req ->
                req.queueUrl(queueUrl).receiptHandle(receiptHandle)
            }
        }.onSuccess {
            structuredLogger.info(
                logType = LogType.INTEGRATION,
                result = LogResult.SUCCESS,
                payload = mapOf(
                    WorkerLogPayloadKeys.PHASE to "delete_message",
                    WorkerLogPayloadKeys.QUEUE_URL to queueUrl.substringAfterLast("/"),
                    WorkerLogPayloadKeys.SQS_MESSAGE_ID to sqsMessageId
                )
            )
        }.onFailure { e ->
            structuredLogger.error(
                logType = LogType.INTEGRATION,
                result = LogResult.FAIL,
                payload = mapOf(
                    WorkerLogPayloadKeys.PHASE to "delete_message",
                    WorkerLogPayloadKeys.QUEUE_URL to queueUrl.substringAfterLast("/"),
                    WorkerLogPayloadKeys.SQS_MESSAGE_ID to sqsMessageId
                ),
                error = e
            )
            throw e
        }
    }
}
