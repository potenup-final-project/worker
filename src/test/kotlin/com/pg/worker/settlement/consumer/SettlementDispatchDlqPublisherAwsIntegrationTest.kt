package com.pg.worker.settlement.consumer

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.gop.logging.contract.StructuredLogger
import com.pg.worker.testsupport.AwsTestEnv
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityRequest
import software.amazon.awssdk.services.sqs.model.Message
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName
import software.amazon.awssdk.services.sqs.model.QueueAttributeName
import java.time.Duration
import java.time.Instant
import java.util.UUID

class SettlementDispatchDlqPublisherAwsIntegrationTest {

    private lateinit var dlqUrl: String
    private lateinit var sourceQueueUrl: String
    private lateinit var sqsClient: SqsClient
    private lateinit var publisher: SettlementDispatchDlqPublisher

    @BeforeEach
    fun setup() {
        dlqUrl = AwsTestEnv.get("SETTLEMENT_SQS_DLQ_URL") ?: ""
        sourceQueueUrl = AwsTestEnv.get("SETTLEMENT_SQS_QUEUE_URL") ?: "integration-test-source"

        assumeTrue(dlqUrl.isNotBlank(), "SETTLEMENT_SQS_DLQ_URL required")

        sqsClient = AwsTestEnv.createSqsClient()
        verifyQueueAccessible(dlqUrl)

        publisher = SettlementDispatchDlqPublisher(
            sqsClient = sqsClient,
            objectMapper = ObjectMapper().registerKotlinModule(),
            structuredLogger = mockk<StructuredLogger>(relaxed = true),
            dlqUrl = dlqUrl
        )
    }

    @Test
    fun `publish sends message with failure attributes to aws dlq`() {
        val marker = "it-dlq-${UUID.randomUUID()}"
        val reason = "INTEGRATION_TEST_NON_RETRYABLE"
        val body = "{\"marker\":\"$marker\",\"kind\":\"direct\"}"

        val published = publisher.publish(sourceQueueUrl, body, reason)
        assertTrue(published, "DLQ publish failed. Check AWS creds/region/permission for queue: $dlqUrl")

        val found = findMessageByMarker(marker, Duration.ofSeconds(30))
        assertNotNull(found, "Expected message marker=$marker in DLQ")

        val message = found!!
        assertEquals(reason, message.messageAttributes()["failureReason"]?.stringValue())
        assertEquals(sourceQueueUrl, message.messageAttributes()["sourceQueueUrl"]?.stringValue())

        sqsClient.deleteMessage { it.queueUrl(dlqUrl).receiptHandle(message.receiptHandle()) }
    }

    @Test
    fun `publishRetryExhausted sends retry-exhausted envelope to aws dlq`() {
        val markerEventId = UUID.randomUUID().toString()
        val reason = "MAX_RETRY_EXHAUSTED_TEST"

        val published = publisher.publishRetryExhausted(
            originalQueueUrl = sourceQueueUrl,
            eventId = markerEventId,
            merchantId = 999L,
            rawId = 12345L,
            retryCount = 9,
            failureReason = reason,
        )
        assertTrue(published, "DLQ retry-exhausted publish failed. Check AWS creds/permission for queue: $dlqUrl")

        val found = findMessageByMarker(markerEventId, Duration.ofSeconds(30))
        assertNotNull(found, "Expected retry-exhausted eventId=$markerEventId in DLQ")

        val message = found!!
        assertEquals(
            "MAX_RETRY_EXHAUSTED:$reason",
            message.messageAttributes()["failureReason"]?.stringValue()
        )
        assertEquals(sourceQueueUrl, message.messageAttributes()["sourceQueueUrl"]?.stringValue())

        sqsClient.deleteMessage { it.queueUrl(dlqUrl).receiptHandle(message.receiptHandle()) }
    }

    private fun findMessageByMarker(marker: String, timeout: Duration): Message? {
        val deadline = Instant.now().plus(timeout)
        while (Instant.now().isBefore(deadline)) {
            val messages = sqsClient.receiveMessage { req ->
                req.queueUrl(dlqUrl)
                    .maxNumberOfMessages(10)
                    .waitTimeSeconds(2)
                    .messageSystemAttributeNames(MessageSystemAttributeName.SENT_TIMESTAMP)
                    .messageAttributeNames("All")
            }.messages()

            for (message in messages) {
                if (message.body().contains(marker)) {
                    return message
                }

                sqsClient.changeMessageVisibility(
                    ChangeMessageVisibilityRequest.builder()
                        .queueUrl(dlqUrl)
                        .receiptHandle(message.receiptHandle())
                        .visibilityTimeout(0)
                        .build()
                )
            }
        }

        return null
    }

    private fun verifyQueueAccessible(queueUrl: String) {
        runCatching {
            sqsClient.getQueueAttributes { req ->
                req.queueUrl(queueUrl).attributeNames(QueueAttributeName.QUEUE_ARN)
            }
        }.onFailure {
            throw IllegalStateException(
                "Cannot access queue=$queueUrl. Check AWS credentials, region, and SQS permission.",
                it
            )
        }
    }
}
