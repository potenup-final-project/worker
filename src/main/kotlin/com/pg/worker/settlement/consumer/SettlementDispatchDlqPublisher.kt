package com.pg.worker.settlement.consumer

import com.fasterxml.jackson.databind.ObjectMapper
import com.gop.logging.contract.LogPrefix
import com.gop.logging.contract.LogResult
import com.gop.logging.contract.LogSuffix
import com.gop.logging.contract.LogType
import com.gop.logging.contract.StepPrefix
import com.gop.logging.contract.StructuredLogger
import com.pg.worker.global.logging.WorkerLogPayloadKeys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue

@Component
@LogPrefix(StepPrefix.SETTLEMENT_LEDGER)
class SettlementDispatchDlqPublisher(
    private val sqsClient: SqsClient,
    private val objectMapper: ObjectMapper,
    private val structuredLogger: StructuredLogger,
    @Value("\${settlement.sqs.dlq-url}") private val dlqUrl: String,
) {
    init {
        require(dlqUrl.isNotBlank()) { "settlement.sqs.dlq-url must be configured when settlement.sqs.enabled=true" }
    }

    @LogSuffix("dlqPublish")
    fun publish(originalQueueUrl: String, messageBody: String, failureReason: String): Boolean {
        structuredLogger.warn(
            logType = LogType.INTEGRATION,
            result = LogResult.START,
            payload = mapOf(
                WorkerLogPayloadKeys.PHASE to "dlq_publish_start",
                WorkerLogPayloadKeys.DLQ_URL to dlqUrl,
                WorkerLogPayloadKeys.SOURCE_QUEUE_URL to originalQueueUrl,
                WorkerLogPayloadKeys.REASON to failureReason
            )
        )
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
            structuredLogger.info(
                logType = LogType.INTEGRATION,
                result = LogResult.SUCCESS,
                payload = mapOf(
                    WorkerLogPayloadKeys.PHASE to "dlq_publish_success",
                    WorkerLogPayloadKeys.DLQ_URL to dlqUrl,
                    WorkerLogPayloadKeys.SOURCE_QUEUE_URL to originalQueueUrl,
                    WorkerLogPayloadKeys.REASON to failureReason
                )
            )
            true
        }.onFailure { e ->
            structuredLogger.error(
                logType = LogType.INTEGRATION,
                result = LogResult.FAIL,
                payload = mapOf(
                    WorkerLogPayloadKeys.PHASE to "dlq_publish_failure",
                    WorkerLogPayloadKeys.DLQ_URL to dlqUrl,
                    WorkerLogPayloadKeys.SOURCE_QUEUE_URL to originalQueueUrl,
                    WorkerLogPayloadKeys.REASON to failureReason
                ),
                error = e
            )
        }.getOrDefault(false)
    }

    @LogSuffix("dlqPublishRetryExhausted")
    fun publishRetryExhausted(
        originalQueueUrl: String,
        eventId: String,
        merchantId: Long,
        rawId: Long,
        retryCount: Int,
        failureReason: String,
    ): Boolean {
        val body = objectMapper.writeValueAsString(
            mapOf(
                WorkerLogPayloadKeys.SCHEMA_VERSION to 1,
                WorkerLogPayloadKeys.EVENT_TYPE to "SETTLEMENT_RETRY_EXHAUSTED",
                WorkerLogPayloadKeys.EVENT_ID to eventId,
                WorkerLogPayloadKeys.MESSAGE_ID to eventId,
                WorkerLogPayloadKeys.MERCHANT_ID to merchantId,
                "payload" to mapOf(
                    WorkerLogPayloadKeys.RAW_ID to rawId,
                    WorkerLogPayloadKeys.RETRY_COUNT to retryCount,
                    WorkerLogPayloadKeys.FAILURE_REASON to failureReason
                )
            )
        )
        return publish(
            originalQueueUrl = originalQueueUrl,
            messageBody = body,
            failureReason = "MAX_RETRY_EXHAUSTED:$failureReason"
        )
    }
}
