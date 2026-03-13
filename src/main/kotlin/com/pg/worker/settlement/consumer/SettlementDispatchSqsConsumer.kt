package com.pg.worker.settlement.consumer

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import software.amazon.awssdk.services.sqs.SqsClient

@Component
@ConditionalOnProperty(prefix = "settlement.sqs", name = ["enabled"], havingValue = "true")
class SettlementDispatchSqsConsumer(
    private val sqsClient: SqsClient,
    private val handler: SettlementDispatchMessageHandler,
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
    }

    @Scheduled(fixedDelayString = "\${settlement.sqs.poll-interval-ms}", scheduler = "sqsPollingScheduler")
    fun poll() {
        val messages = sqsClient.receiveMessage { req ->
            req.queueUrl(queueUrl)
                .maxNumberOfMessages(maxMessages)
                .waitTimeSeconds(waitTimeSeconds)
                .visibilityTimeout(visibilityTimeoutSeconds)
        }.messages()

        if (messages.isNotEmpty()) {
            log.info("[PaymentDispatchSqsConsumer] received {} messages from queue.", messages.size)
        }

        messages.forEach { message ->
            val success = runCatching { handler.handle(message.body()) }
                .onFailure { e -> log.error("[PaymentDispatchSqsConsumer] message handle failed", e) }
                .getOrDefault(false)

            if (success) {
                sqsClient.deleteMessage { req ->
                    req.queueUrl(queueUrl).receiptHandle(message.receiptHandle())
                }
            }
        }
    }
}
