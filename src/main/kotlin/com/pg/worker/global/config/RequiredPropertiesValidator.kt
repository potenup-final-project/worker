package com.pg.worker.global.config

import com.pg.worker.global.exception.CommonErrorCode
import jakarta.annotation.PostConstruct
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

@Component
class RequiredPropertiesValidator(
    private val environment: Environment,
) {
    @PostConstruct
    fun validate() {
        val baseRequired = listOf(
            "DB_USER",
            "DB_PASSWORD",
            "AWS_REGION",
            "WORKER_BATCH_SIZE",
            "WORKER_INTERVAL_MS",
            "WORKER_MAX_ATTEMPTS",
            "WORKER_CONCURRENCY_ENDPOINT",
            "WORKER_SEND_THREAD",
            "LEASE_MINUTES",
            "SWEEP_INTERVAL_MS",
            "HTTP_CONN_TIMEOUT",
            "HTTP_READ_TIMEOUT",
            "WEBHOOK_SECRET_KEY",
            "WEBHOOK_ALLOW_PLAINTEXT",
            "WEBHOOK_REQUIRE_HTTPS",
            "WEBHOOK_LIMITER_REDIS_MAX_PERMITS",
            "WEBHOOK_LIMITER_REDIS_PERMIT_TTL_MS",
            "WEBHOOK_LIMITER_REDIS_FAIL_OPEN",
            "REDIS_HOST",
            "REDIS_PORT",
        )

        val missingBase = baseRequired.filter { environment.getProperty(it).isNullOrBlank() }
        if (missingBase.isNotEmpty()) {
            throw IllegalStateException("${CommonErrorCode.BASE_PROPERTIES_INVALID.message}: ${missingBase.joinToString(", ")}")
        }

        // Webhook SQS validation
        val webhookSqsEnabled = environment.getProperty("WEBHOOK_SQS_ENABLED")?.toBooleanStrictOrNull() == true
        if (webhookSqsEnabled) {
            val webhookRequired = listOf(
                "WEBHOOK_SQS_QUEUE_URL",
                "WEBHOOK_SQS_POLL_INTERVAL_MS",
                "WEBHOOK_SQS_MAX_MESSAGES",
                "WEBHOOK_SQS_WAIT_SECONDS",
                "WEBHOOK_SQS_VISIBILITY_TIMEOUT_SECONDS",
            )
            val missingWebhook = webhookRequired.filter { environment.getProperty(it).isNullOrBlank() }
            if (missingWebhook.isNotEmpty()) {
                throw IllegalStateException("${CommonErrorCode.BASE_PROPERTIES_INVALID.message} (Webhook SQS): ${missingWebhook.joinToString(", ")}")
            }
        }

        // Settlement SQS validation
        val settlementSqsEnabled = environment.getProperty("SETTLEMENT_SQS_ENABLED")?.toBooleanStrictOrNull() == true
        if (settlementSqsEnabled) {
            val settlementRequired = listOf(
                "SETTLEMENT_SQS_QUEUE_URL",
                "SETTLEMENT_SQS_POLL_INTERVAL_MS",
                "SETTLEMENT_SQS_MAX_MESSAGES",
                "SETTLEMENT_SQS_WAIT_SECONDS",
                "SETTLEMENT_SQS_VISIBILITY_TIMEOUT_SECONDS",
            )
            val missingSettlement = settlementRequired.filter { environment.getProperty(it).isNullOrBlank() }
            if (missingSettlement.isNotEmpty()) {
                throw IllegalStateException("${CommonErrorCode.BASE_PROPERTIES_INVALID.message} (Settlement SQS): ${missingSettlement.joinToString(", ")}")
            }
        }
    }
}
