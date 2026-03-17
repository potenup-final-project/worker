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
import com.pg.worker.global.logging.support.ErrorClassifier
import com.pg.worker.global.logging.support.LogSanitizer
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.reflect.MethodSignature
import com.gop.logging.contract.StructuredLogger
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Aspect
@Component("workerTechnicalLoggingAspect")
class TechnicalLoggingAspect(
    private val objectMapper: ObjectMapper,
    @Value("\${logging.pattern.slow-threshold-ms}")
    private val slowThresholdMs: Long,
    private val log: StructuredLogger) {

    @Around(
        "execution(public * com.pg.worker..infra..client..*(..)) || " +
                "execution(public * com.pg.worker..infra..persistence..*(..)) || " +
                "execution(public * com.pg.worker..infra..messaging..*(..)) || " +
                "execution(public * com.pg.worker..infra..redis..*(..))"
    )
    fun around(joinPoint: ProceedingJoinPoint): Any? {
        val signature = joinPoint.signature as MethodSignature

        if (signature.method.isAnnotationPresent(BusinessLog::class.java)) {
            return joinPoint.proceed()
        }

        val start = System.currentTimeMillis()

        return try {
            val result = joinPoint.proceed()
            val durationMs = System.currentTimeMillis() - start

            if (durationMs >= slowThresholdMs && !isExpectedLongPolling(signature)) {
                log.warn(serialize(successPayload(signature, durationMs)))
            }

            result
        } catch (e: Exception) {
            val durationMs = System.currentTimeMillis() - start
            log.error(serialize(errorPayload(signature, durationMs, e)), e)
            throw e
        }
    }

    private fun successPayload(
        signature: MethodSignature,
        durationMs: Long,
    ): Map<String, Any?> {
        return linkedMapOf(
            "logType" to "TECHNICAL",
            "result" to "SUCCESS",
            "className" to signature.declaringType.simpleName,
            "methodName" to signature.method.name,
            "layer" to resolveLayer(signature),
            "durationMs" to durationMs,
            "slowThresholdMs" to slowThresholdMs,
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
    }

    private fun errorPayload(
        signature: MethodSignature,
        durationMs: Long,
        e: Exception,
    ): Map<String, Any?> {
        return linkedMapOf(
            "logType" to "TECHNICAL",
            "result" to "FAIL",
            "className" to signature.declaringType.simpleName,
            "methodName" to signature.method.name,
            "layer" to resolveLayer(signature),
            "durationMs" to durationMs,
            WorkerLogPayloadKeys.TRACE_ID to MDC.get(MDC_TRACE_ID),
            "orderFlowId" to MDC.get(MDC_ORDER_FLOW_ID),
            WorkerLogPayloadKeys.EVENT_TYPE to MDC.get(MDC_EVENT_TYPE),
            WorkerLogPayloadKeys.MESSAGE_ID to MDC.get(MDC_MESSAGE_ID),
            "queue" to MDC.get(MDC_QUEUE),
            "topic" to MDC.get(MDC_TOPIC),
            "consumer" to MDC.get(MDC_CONSUMER),
            WorkerLogPayloadKeys.RETRY_COUNT to MDC.get(MDC_RETRY_COUNT),
            "redelivered" to MDC.get(MDC_REDELIVERED),
            "errorType" to e.javaClass.simpleName,
            "errorCategory" to ErrorClassifier.classify(e),
            "errorMessage" to LogSanitizer.sanitizeErrorMessage(e.message),
        )
    }

    private fun resolveLayer(signature: MethodSignature): String {
        val typeName = signature.declaringTypeName
        return when {
            typeName.contains(".infra.") && typeName.contains(".client.") -> "INFRA_CLIENT"
            typeName.contains(".infra.") && typeName.contains(".messaging.") -> "INFRA_MESSAGING"
            typeName.contains(".infra.") && typeName.contains(".redis.") -> "INFRA_REDIS"
            typeName.contains(".infra.") && typeName.contains(".persistence.") -> "INFRA_PERSISTENCE"
            typeName.contains(".infra.") -> "INFRA"
            else -> "UNKNOWN"
        }
    }

    private fun isExpectedLongPolling(signature: MethodSignature): Boolean {
        val className = signature.declaringType.simpleName
        return signature.method.name == "poll" && (
            className == "WebhookDispatchSqsConsumer" ||
                className == "SettlementDispatchSqsConsumer"
            )
    }

    private fun serialize(payload: Map<String, Any?>): String {
        return runCatching { objectMapper.writeValueAsString(payload) }
            .getOrElse {
                """{"logType":"TECHNICAL","message":"Failed to serialize technical log payload"}"""
            }
    }
}
