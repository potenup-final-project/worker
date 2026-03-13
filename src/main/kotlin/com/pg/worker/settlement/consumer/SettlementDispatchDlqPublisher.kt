package com.pg.worker.settlement.consumer

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue

@Component
class SettlementDispatchDlqPublisher(
    private val sqsClient: SqsClient,
    @Value("\${settlement.sqs.dlq-url}") private val dlqUrl: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    init {
        require(dlqUrl.isNotBlank()) { "settlement.sqs.dlq-url must be configured when settlement.sqs.enabled=true" }
    }

    fun publish(originalQueueUrl: String, messageBody: String, failureReason: String): Boolean {
        return runCatching {
            sqsClient.sendMessage { req ->
                req.queueUrl(dlqUrl)
                    .messageBody(messageBody)
                    .messageAttributes(
                        mapOf(
                            "failureReason" to MessageAttributeValue.builder()
                                .dataType("String")
                                .stringValue(failureReason)
                                .build(),
                            "sourceQueueUrl" to MessageAttributeValue.builder()
                                .dataType("String")
                                .stringValue(originalQueueUrl)
                                .build(),
                        )
                    )
            }
            log.warn("[SettlementDispatchDlqPublisher] message forwarded to DLQ. reason={}", failureReason)
            true
        }.onFailure { e ->
            log.error("[SettlementDispatchDlqPublisher] failed to publish message to DLQ", e)
        }.getOrDefault(false)
    }
}
