package com.pg.worker.webhook.infra.messaging.consumer

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

@Component
@ConditionalOnProperty(prefix = "webhook.sqs", name = ["enabled"], havingValue = "true")
@LogPrefix(StepPrefix.WEBHOOK_DELIVERY)
class WebhookDispatchSqsConsumer(
    private val sqsClient: SqsClient,
    private val handler: WebhookDispatchMessageHandler,
    private val structuredLogger: StructuredLogger,
    @Value("\${webhook.sqs.queue-url}") private val queueUrl: String,
    @Value("\${webhook.sqs.max-messages}") private val maxMessages: Int,
    @Value("\${webhook.sqs.wait-time-seconds}") private val waitTimeSeconds: Int,
    @Value("\${webhook.sqs.visibility-timeout-seconds}") private val visibilityTimeoutSeconds: Int,
) {
    init {
        require(queueUrl.isNotBlank()) { "webhook.sqs.queue-url must be configured when webhook.sqs.enabled=true" }
        require(maxMessages in 1..10) { "webhook.sqs.max-messages must be between 1 and 10" }
        require(waitTimeSeconds in 0..20) { "webhook.sqs.wait-time-seconds must be between 0 and 20" }
        require(visibilityTimeoutSeconds >= 10) {
            "webhook.sqs.visibility-timeout-seconds must be at least 10 seconds"
        }
        structuredLogger.info(
            logType = LogType.TECHNICAL,
            result = LogResult.SUCCESS,
            payload = mapOf(
                WorkerLogPayloadKeys.PHASE to "init",
                WorkerLogPayloadKeys.QUEUE_URL to queueUrl,
                WorkerLogPayloadKeys.POLL_MAX to maxMessages,
                WorkerLogPayloadKeys.WAIT_SEC to waitTimeSeconds,
                WorkerLogPayloadKeys.VISIBILITY_SEC to visibilityTimeoutSeconds
            )
        )
    }

    @Scheduled(fixedDelayString = "\${webhook.sqs.poll-interval-ms}", scheduler = "webhookSqsPollingScheduler")
    @LogSuffix("poll")
    fun poll() {
        val messages = sqsClient.receiveMessage { req ->
            req.queueUrl(queueUrl)
                .maxNumberOfMessages(maxMessages)
                .waitTimeSeconds(waitTimeSeconds)
                .visibilityTimeout(visibilityTimeoutSeconds)
        }.messages()

        if (messages.isNotEmpty()) {
            structuredLogger.info(
                logType = LogType.INTEGRATION,
                result = LogResult.SUCCESS,
                payload = mapOf(
                    WorkerLogPayloadKeys.PHASE to "receive",
                    WorkerLogPayloadKeys.QUEUE_URL to queueUrl,
                    WorkerLogPayloadKeys.RECEIVED_COUNT to messages.size
                )
            )
        }

        messages.forEach { message ->
            MDC.put(LogMdcKeys.MESSAGE_ID, message.messageId())
            val success = runCatching { handler.handle(message.body()) }
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
                .getOrDefault(false)

            if (success) {
                sqsClient.deleteMessage { req ->
                    req.queueUrl(queueUrl).receiptHandle(message.receiptHandle())
                }
                structuredLogger.info(
                    logType = LogType.INTEGRATION,
                    result = LogResult.SUCCESS,
                    payload = mapOf(
                        WorkerLogPayloadKeys.PHASE to "delete_message",
                        WorkerLogPayloadKeys.QUEUE_URL to queueUrl,
                        WorkerLogPayloadKeys.SQS_MESSAGE_ID to message.messageId()
                    )
                )
            } else {
                structuredLogger.warn(
                    logType = LogType.INTEGRATION,
                    result = LogResult.RETRY,
                    payload = mapOf(
                        WorkerLogPayloadKeys.PHASE to "handle_result",
                        WorkerLogPayloadKeys.QUEUE_URL to queueUrl,
                        WorkerLogPayloadKeys.SQS_MESSAGE_ID to message.messageId(),
                        WorkerLogPayloadKeys.REASON to "handler_returned_false"
                    )
                )
            }
            MDC.remove(LogMdcKeys.MESSAGE_ID)
        }
    }
}
