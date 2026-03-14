package com.pg.worker.settlement.consumer

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.gop.logging.contract.StructuredLogger
import com.pg.worker.testsupport.AwsTestEnv
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityRequest
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest
import software.amazon.awssdk.services.sqs.model.Message
import software.amazon.awssdk.services.sqs.model.QueueAttributeName
import java.time.Duration
import java.time.Instant
import java.util.UUID

class SettlementDispatchSqsConsumerAwsIntegrationTest {

    private lateinit var queueUrl: String
    private lateinit var dlqUrl: String
    private lateinit var sqsClient: SqsClient
    private lateinit var handler: SettlementDispatchMessageHandler
    private lateinit var structuredLogger: StructuredLogger
    private lateinit var consumer: SettlementDispatchSqsConsumer

    @BeforeEach
    fun setup() {
        queueUrl = AwsTestEnv.get("AWS_TEST_SETTLEMENT_QUEUE_URL") ?: ""
        dlqUrl = AwsTestEnv.get("AWS_TEST_SETTLEMENT_DLQ_URL") ?: ""

        assumeTrue(queueUrl.isNotBlank(), "AWS_TEST_SETTLEMENT_QUEUE_URL required")
        assumeTrue(dlqUrl.isNotBlank(), "AWS_TEST_SETTLEMENT_DLQ_URL required")

        sqsClient = AwsTestEnv.createSqsClient()
        verifyQueueAccessible(queueUrl)
        verifyQueueAccessible(dlqUrl)

        handler = mockk()
        structuredLogger = mockk(relaxed = true)
        consumer = SettlementDispatchSqsConsumer(
            sqsClient = sqsClient,
            handler = handler,
            dlqPublisher = SettlementDispatchDlqPublisher(
                sqsClient = sqsClient,
                objectMapper = ObjectMapper().registerKotlinModule(),
                structuredLogger = structuredLogger,
                dlqUrl = dlqUrl,
            ),
            structuredLogger = structuredLogger,
            queueUrl = queueUrl,
            maxMessages = 10,
            waitTimeSeconds = 1,
            visibilityTimeoutSeconds = 30,
        )

        purgeQueue(queueUrl)
        purgeQueue(dlqUrl)
    }

    @Test
    fun `non-retryable message is routed to aws dlq and removed from main queue`() {
        val marker = "it-consumer-${UUID.randomUUID()}"
        val reason = "INTEGRATION_TEST_NON_RETRYABLE"
        val body = "{\"marker\":\"$marker\",\"kind\":\"consumer\"}"

        every { handler.handle(any()) } answers {
            val messageBody = firstArg<String>()
            if (messageBody.contains(marker)) {
                SettlementDispatchHandleResult.NonRetryableFailure(reason)
            } else {
                SettlementDispatchHandleResult.Success
            }
        }

        sqsClient.sendMessage { it.queueUrl(queueUrl).messageBody(body) }

        repeat(5) {
            consumer.poll()
            Thread.sleep(500)
        }

        val dlqMessage = findMessageByMarker(dlqUrl, marker, Duration.ofSeconds(20))
        assertNotNull(dlqMessage, "Expected marker=$marker in DLQ")

        val message = dlqMessage!!
        assertEquals(reason, message.messageAttributes()["failureReason"]?.stringValue())
        assertEquals(queueUrl, message.messageAttributes()["sourceQueueUrl"]?.stringValue())
        assertTrue(message.body().contains(marker), "DLQ message body does not contain expected marker")

        sqsClient.deleteMessage(
            DeleteMessageRequest.builder().queueUrl(dlqUrl).receiptHandle(message.receiptHandle()).build()
        )
    }

    private fun findMessageByMarker(targetQueueUrl: String, marker: String, timeout: Duration): Message? {
        val deadline = Instant.now().plus(timeout)
        while (Instant.now().isBefore(deadline)) {
            val messages = sqsClient.receiveMessage { req ->
                req.queueUrl(targetQueueUrl)
                    .maxNumberOfMessages(10)
                    .waitTimeSeconds(2)
                    .messageAttributeNames("All")
            }.messages()

            for (message in messages) {
                if (message.body().contains(marker)) {
                    return message
                }

                sqsClient.changeMessageVisibility(
                    ChangeMessageVisibilityRequest.builder()
                        .queueUrl(targetQueueUrl)
                        .receiptHandle(message.receiptHandle())
                        .visibilityTimeout(0)
                        .build()
                )
            }
        }
        return null
    }

    private fun purgeQueue(targetQueueUrl: String) {
        val deadline = Instant.now().plusSeconds(10)
        while (Instant.now().isBefore(deadline)) {
            val messages = sqsClient.receiveMessage { req ->
                req.queueUrl(targetQueueUrl)
                    .maxNumberOfMessages(10)
                    .waitTimeSeconds(1)
            }.messages()

            if (messages.isEmpty()) return

            messages.forEach { msg ->
                sqsClient.deleteMessage {
                    it.queueUrl(targetQueueUrl).receiptHandle(msg.receiptHandle())
                }
            }
        }
    }

    private fun verifyQueueAccessible(targetQueueUrl: String) {
        runCatching {
            sqsClient.getQueueAttributes { req ->
                req.queueUrl(targetQueueUrl).attributeNames(QueueAttributeName.QUEUE_ARN)
            }
        }.onFailure {
            throw IllegalStateException(
                "Cannot access queue=$targetQueueUrl. Check AWS credentials, region, and SQS permission.",
                it
            )
        }
    }
}
