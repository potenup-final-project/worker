package com.pg.worker.global.logging.aspect

import com.fasterxml.jackson.databind.ObjectMapper
import com.pg.worker.global.logging.MDC_CONSUMER
import com.pg.worker.global.logging.MDC_EVENT_TYPE
import com.pg.worker.global.logging.MDC_MESSAGE_ID
import com.pg.worker.global.logging.MDC_ORDER_FLOW_ID
import com.pg.worker.global.logging.MDC_QUEUE
import com.pg.worker.global.logging.MDC_REDELIVERED
import com.pg.worker.global.logging.MDC_RETRY_COUNT
import com.pg.worker.global.logging.MDC_TOPIC
import com.pg.worker.global.logging.MDC_TRACE_ID
import com.pg.worker.global.logging.WorkerLogPayloadKeys
import com.pg.worker.global.logging.annotation.BusinessLog
import com.pg.worker.global.logging.context.LogContextHolder
import com.pg.worker.global.logging.support.ErrorClassifier
import com.pg.worker.global.logging.support.LogSanitizer
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.reflect.MethodSignature
import com.gop.logging.contract.StructuredLogger
import org.slf4j.MDC
import org.springframework.stereotype.Component
import java.time.Instant

@Aspect
@Component
class BusinessLogAspect(
    private val objectMapper: ObjectMapper,
    private val log: StructuredLogger) {

    private val allowedArgNames = setOf(
        "paymentKey",
        "amount",
        "userId",
        "merchantId",
        "webhookId",
        "settlementId",
    )

    @Around("@annotation(businessLog)")
    fun around(
        joinPoint: ProceedingJoinPoint,
        businessLog: BusinessLog,
    ): Any? {
        val start = System.currentTimeMillis()
        val startedAt = Instant.now()
        val signature = joinPoint.signature as MethodSignature

        return try {
            val result = joinPoint.proceed()
            val durationMs = System.currentTimeMillis() - start

            if (businessLog.logOnSuccess) {
                val payload = basePayload(
                    businessLog = businessLog,
                    signature = signature,
                    result = "SUCCESS",
                    durationMs = durationMs,
                    startedAt = startedAt,
                    finishedAt = Instant.now(),
                    args = joinPoint.args,
                )
                safeLogInfo(payload)
            }

            result
        } catch (e: Exception) {
            val durationMs = System.currentTimeMillis() - start

            if (businessLog.logOnFailure) {
                val payload = basePayload(
                    businessLog = businessLog,
                    signature = signature,
                    result = "FAIL",
                    durationMs = durationMs,
                    startedAt = startedAt,
                    finishedAt = Instant.now(),
                    args = joinPoint.args,
                ).toMutableMap().apply {
                    put("errorType", e.javaClass.simpleName)
                    put("errorCategory", ErrorClassifier.classify(e))
                    put("errorMessage", LogSanitizer.sanitizeErrorMessage(e.message))
                }

                safeLogError(payload, e)
            }

            throw e
        } finally {
            LogContextHolder.clear()
        }
    }

    private fun basePayload(
        businessLog: BusinessLog,
        signature: MethodSignature,
        result: String,
        durationMs: Long,
        startedAt: Instant,
        finishedAt: Instant,
        args: Array<Any?>,
    ): Map<String, Any?> {
        val payload = linkedMapOf<String, Any?>(
            "logType" to "BUSINESS",
            "result" to result,
            "className" to signature.declaringType.simpleName,
            "methodName" to signature.method.name,
            "eventCategory" to businessLog.category,
            "eventName" to businessLog.event,
            "durationMs" to durationMs,
            "startedAt" to startedAt.toString(),
            "finishedAt" to finishedAt.toString(),
            WorkerLogPayloadKeys.TRACE_ID to MDC.get(MDC_TRACE_ID),
            "orderFlowId" to MDC.get(MDC_ORDER_FLOW_ID),
            WorkerLogPayloadKeys.EVENT_TYPE to MDC.get(MDC_EVENT_TYPE),
            WorkerLogPayloadKeys.MESSAGE_ID to MDC.get(MDC_MESSAGE_ID),
            "queue" to MDC.get(MDC_QUEUE),
            "topic" to MDC.get(MDC_TOPIC),
            "consumer" to MDC.get(MDC_CONSUMER),
            WorkerLogPayloadKeys.RETRY_COUNT to MDC.get(MDC_RETRY_COUNT),
            "redelivered" to MDC.get(MDC_REDELIVERED),
        )

        val parameterNames = signature.parameterNames ?: emptyArray()
        val extractedArgs = extractArgs(parameterNames, args)

        if (extractedArgs.isNotEmpty()) {
            payload["args"] = extractedArgs
        }

        payload.putAll(LogContextHolder.getAll())
        return payload
    }

    private fun extractArgs(
        names: Array<String>,
        values: Array<Any?>,
    ): Map<String, Any?> {
        return names.zip(values)
            .filter { (name, _) -> name in allowedArgNames }
            .associate { (name, value) -> name to value }
    }

    private fun safeLogInfo(payload: Map<String, Any?>) {
        runCatching { objectMapper.writeValueAsString(payload) }
            .onSuccess { log.info(it) }
            .onFailure { log.warn("Failed to serialize business log payload", it) }
    }

    private fun safeLogError(
        payload: Map<String, Any?>,
        e: Exception,
    ) {
        runCatching { objectMapper.writeValueAsString(payload) }
            .onSuccess { log.error(it, e) }
            .onFailure { log.error("Failed to serialize business log payload", e) }
    }
}
