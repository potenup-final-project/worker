package com.pg.worker.webhook.consumer

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import software.amazon.awssdk.services.sqs.SqsClient

@Component
@ConditionalOnProperty(prefix = "webhook.sqs", name = ["enabled"], havingValue = "true")
class WebhookDispatchSqsConsumer(
    private val sqsClient: SqsClient,
    private val handler: WebhookDispatchMessageHandler,
    @Value("\${webhook.sqs.queue-url}") private val queueUrl: String,
    @Value("\${webhook.sqs.max-messages:10}") private val maxMessages: Int,
    @Value("\${webhook.sqs.wait-time-seconds:20}") private val waitTimeSeconds: Int,
    @Value("\${webhook.sqs.visibility-timeout-seconds:30}") private val visibilityTimeoutSeconds: Int,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    init {
        require(queueUrl.isNotBlank()) { "webhook.sqs.queue-url must be configured when webhook.sqs.enabled=true" }
    }

    @Scheduled(fixedDelayString = "\${webhook.sqs.poll-interval-ms:1000}")
    fun poll() {
        val messages = sqsClient.receiveMessage { req ->
            req.queueUrl(queueUrl)
                .maxNumberOfMessages(maxMessages)
                .waitTimeSeconds(waitTimeSeconds)
                .visibilityTimeout(visibilityTimeoutSeconds)
        }.messages()

        messages.forEach { message ->
            val success = runCatching { handler.handle(message.body()) }
                .onFailure { e -> log.error("[WebhookDispatchSqsConsumer] message handle failed", e) }
                .getOrDefault(false)

            if (success) {
                sqsClient.deleteMessage { req ->
                    req.queueUrl(queueUrl).receiptHandle(message.receiptHandle())
                }
            }
        }
    }
}
