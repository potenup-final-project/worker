package com.pg.worker.global.logging

import com.fasterxml.jackson.databind.ObjectMapper
import com.pg.worker.global.logging.support.LogSanitizer
import com.pg.worker.global.logging.context.WorkerExecutionResult
import com.pg.worker.global.logging.context.WorkerMessageContext
import com.pg.worker.global.logging.context.WorkerResult
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.stereotype.Component

@Component
class MessageLogExecutor(
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(MessageLogExecutor::class.java)

    fun <T> execute(
        context: WorkerMessageContext,
        block: () -> WorkerExecutionResult<T>,
    ): T? {
        val startedAt = System.currentTimeMillis()

        putMdc(context)
        logStart(context)

        return try {
            val executionResult = block()
            val durationMs = System.currentTimeMillis() - startedAt

            logEnd(
                context = context,
                result = executionResult.result,
                durationMs = durationMs,
                error = executionResult.error,
            )

            if (executionResult.error != null) {
                throw executionResult.error
            }

            executionResult.data
        } finally {
            MDC.clear()
        }
    }

    private fun putMdc(context: WorkerMessageContext) {
        MDC.put(MDC_TRACE_ID, context.traceId)
        MDC.put(MDC_ORDER_FLOW_ID, context.orderFlowId)
        MDC.put(MDC_EVENT_TYPE, context.eventType)
        MDC.put(MDC_CONSUMER, context.consumer)

        context.messageId?.let { MDC.put(MDC_MESSAGE_ID, it) }
        context.queue?.let { MDC.put(MDC_QUEUE, it) }
        context.topic?.let { MDC.put(MDC_TOPIC, it) }
        context.retryCount?.let { MDC.put(MDC_RETRY_COUNT, it.toString()) }
        context.redelivered?.let { MDC.put(MDC_REDELIVERED, it.toString()) }
    }

    private fun logStart(context: WorkerMessageContext) {
        log.info(
            serialize(
                linkedMapOf(
                    "logType" to "MESSAGE",
                    "phase" to "START",
                    "traceId" to context.traceId,
                    "orderFlowId" to context.orderFlowId,
                    "eventType" to context.eventType,
                    "consumer" to context.consumer,
                    "messageId" to context.messageId,
                    "queue" to context.queue,
                    "topic" to context.topic,
                    "retryCount" to context.retryCount,
                    "redelivered" to context.redelivered,
                ),
            ),
        )
    }

    private fun logEnd(
        context: WorkerMessageContext,
        result: WorkerResult,
        durationMs: Long,
        error: Throwable?,
    ) {
        val payload = linkedMapOf(
            "logType" to "MESSAGE",
            "phase" to "END",
            "result" to result.name,
            "traceId" to context.traceId,
            "orderFlowId" to context.orderFlowId,
            "eventType" to context.eventType,
            "consumer" to context.consumer,
            "messageId" to context.messageId,
            "queue" to context.queue,
            "topic" to context.topic,
            "retryCount" to context.retryCount,
            "redelivered" to context.redelivered,
            "durationMs" to durationMs,
        ).apply {
            if (error != null) {
                put("errorType", error.javaClass.simpleName)
                put("errorMessage", LogSanitizer.sanitizeErrorMessage(error.message))
            }
        }

        val json = serialize(payload)

        when (result) {
            WorkerResult.SUCCESS,
            WorkerResult.SKIPPED,
                -> log.info(json)

            WorkerResult.RETRY,
                -> log.warn(json, error)

            WorkerResult.DLQ,
            WorkerResult.FAIL,
                -> log.error(json, error)
        }
    }

    private fun serialize(payload: Map<String, Any?>): String {
        return runCatching { objectMapper.writeValueAsString(payload) }
            .getOrElse {
                """{"logType":"MESSAGE","message":"Failed to serialize message log payload"}"""
            }
    }
}
