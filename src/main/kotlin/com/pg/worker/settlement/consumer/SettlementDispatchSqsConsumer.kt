package com.pg.worker.settlement.consumer

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName

@Component
@ConditionalOnProperty(prefix = "settlement.sqs", name = ["enabled"], havingValue = "true")
class SettlementDispatchSqsConsumer(
    private val sqsClient: SqsClient,
    private val handler: SettlementDispatchMessageHandler,
    private val dlqPublisher: SettlementDispatchDlqPublisher,
    @Value("\${settlement.sqs.queue-url}") private val queueUrl: String,
    @Value("\${settlement.sqs.max-messages}") private val maxMessages: Int,
    @Value("\${settlement.sqs.wait-time-seconds}") private val waitTimeSeconds: Int,
    @Value("\${settlement.sqs.visibility-timeout-seconds}") private val visibilityTimeoutSeconds: Int,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    init {
        require(queueUrl.isNotBlank()) { "settlement.sqs.queue-url must be configured when settlement.sqs.enabled=true" }
        require(maxMessages in 1..10) { "settlement.sqs.max-messages must be between 1 and 10" }
        require(waitTimeSeconds in 0..20) { "settlement.sqs.wait-time-seconds must be between 0 and 20" }
        require(visibilityTimeoutSeconds >= 10) {
            "settlement.sqs.visibility-timeout-seconds must be at least 10 seconds"
        }
        log.info(
            "[SettlementDispatchSqsConsumer] initialized queueUrl={} pollMax={} waitSec={} visibilitySec={}",
            queueUrl,
            maxMessages,
            waitTimeSeconds,
            visibilityTimeoutSeconds,
        )
    }

    @Scheduled(fixedDelayString = "\${settlement.sqs.poll-interval-ms}", scheduler = "settlementSqsPollingScheduler")
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
            log.info("[SettlementDispatchSqsConsumer] received {} messages from queue.", messages.size)
        }

        messages.forEach { message ->
            val receiveCount = message.attributesAsStrings()[MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT.toString()]
            log.info(
                "[SettlementDispatchSqsConsumer] handling sqsMessageId={} receiveCount={} eventBodySize={}",
                message.messageId(),
                receiveCount,
                message.body().length,
            )
            when (val result = runCatching { handler.handle(message.body()) }
                .onFailure { e -> log.error("[SettlementDispatchSqsConsumer] handler execution failed", e) }
                .getOrElse { SettlementDispatchHandleResult.RetryableFailure("HANDLER_EXCEPTION") }) {
                is SettlementDispatchHandleResult.Success -> {
                    deleteMessage(message.receiptHandle())
                }

                is SettlementDispatchHandleResult.NonRetryableFailure -> {
                    routeToDlqAndDeleteIfSucceeded(message.body(), message.receiptHandle(), result.reason)
                }

                is SettlementDispatchHandleResult.RetryableFailure -> {
                    log.warn("[SettlementDispatchSqsConsumer] retryable failure routed to DLQ. reason={}", result.reason)
                    routeToDlqAndDeleteIfSucceeded(message.body(), message.receiptHandle(), result.reason)
                }
            }
        }
    }

    private fun routeToDlqAndDeleteIfSucceeded(messageBody: String, receiptHandle: String, reason: String) {
        val dlqSent = dlqPublisher.publish(
            originalQueueUrl = queueUrl,
            messageBody = messageBody,
            failureReason = reason,
        )
        if (dlqSent) {
            deleteMessage(receiptHandle)
        } else {
            log.error(
                "[SettlementDispatchSqsConsumer] failed to publish to DLQ, keep message for retry. reason={}",
                reason,
            )
        }
    }

    private fun deleteMessage(receiptHandle: String) {
        runCatching {
            sqsClient.deleteMessage { req ->
                req.queueUrl(queueUrl).receiptHandle(receiptHandle)
            }
        }.onSuccess {
            log.info("[SettlementDispatchSqsConsumer] deleteMessage success queueUrl={}", queueUrl)
        }.onFailure { e ->
            log.error("[SettlementDispatchSqsConsumer] deleteMessage failed queueUrl={}", queueUrl, e)
            throw e
        }
    }
}
