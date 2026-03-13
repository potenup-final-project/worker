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
        val required = listOf(
            "DB_URL",
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
            "WEBHOOK_SQS_ENABLED",
            "WEBHOOK_SQS_POLL_INTERVAL_MS",
            "WEBHOOK_SQS_MAX_MESSAGES",
            "WEBHOOK_SQS_WAIT_SECONDS",
            "WEBHOOK_SQS_VISIBILITY_TIMEOUT_SECONDS",
        )

        val missing = required.filter { environment.getProperty(it).isNullOrBlank() }
        if (missing.isNotEmpty()) {
            throw IllegalStateException("${CommonErrorCode.BASE_PROPERTIES_INVALID.message}: ${missing.joinToString(", ")}")
        }

        val sqsEnabled = environment.getProperty("WEBHOOK_SQS_ENABLED")?.toBooleanStrictOrNull() == true
        if (sqsEnabled) {
            if (environment.getProperty("WEBHOOK_SQS_QUEUE_URL").isNullOrBlank()) {
                throw IllegalStateException(CommonErrorCode.RELAY_SQS_QUEUE_URL_MISSING.message)
            }

            validateAwsCredentials()
        }

        val settlementSqsEnabled = environment.getProperty("SETTLEMENT_SQS_ENABLED")?.toBooleanStrictOrNull() == true
        if (settlementSqsEnabled) {
            val settlementRequired = listOf(
                "SETTLEMENT_SQS_QUEUE_URL",
                "SETTLEMENT_SQS_DLQ_URL",
                "SETTLEMENT_SQS_POLL_INTERVAL_MS",
                "SETTLEMENT_SQS_MAX_MESSAGES",
                "SETTLEMENT_SQS_WAIT_SECONDS",
                "SETTLEMENT_SQS_VISIBILITY_TIMEOUT_SECONDS",
            )
            val missingSettlement = settlementRequired.filter { environment.getProperty(it).isNullOrBlank() }
            if (missingSettlement.isNotEmpty()) {
                throw IllegalStateException("${CommonErrorCode.BASE_PROPERTIES_INVALID.message}: ${missingSettlement.joinToString(", ")}")
            }

            validateAwsCredentials()
        }

        val reconciliationEnabled = environment.getProperty("WEBHOOK_RECON_ENABLED")?.toBooleanStrictOrNull() == true
        if (reconciliationEnabled) {
            val reconciliationRequired = listOf(
                "WEBHOOK_RECON_CHUNK_SIZE",
                "WEBHOOK_RECON_MAX_PAGES",
                "WEBHOOK_RECON_STALE_GRACE_MINUTES",
                "WEBHOOK_RECON_STALE_AGE_HOURS",
                "WEBHOOK_RECON_DEGRADED_WINDOW_DAYS",
                "WEBHOOK_RECON_DEGRADED_MIN_SAMPLE",
                "WEBHOOK_RECON_DEGRADED_DEAD_RATE",
                "WEBHOOK_RECON_DEGRADED_RECOVERY_WINDOW_DAYS",
                "WEBHOOK_RECON_MISSING_GRACE_MINUTES",
                "WEBHOOK_RECON_LOOKBACK_DAYS",
                "WEBHOOK_RECON_AUTO_DDL",
                "PGCORE_READ_DB_URL",
                "PGCORE_READ_DB_USER",
                "PGCORE_READ_DB_PASSWORD",
                "PGCORE_READ_DB_DRIVER",
            )
            val missingReconciliation = reconciliationRequired.filter { environment.getProperty(it).isNullOrBlank() }
            if (missingReconciliation.isNotEmpty()) {
                throw IllegalStateException("${CommonErrorCode.BASE_PROPERTIES_INVALID.message}: ${missingReconciliation.joinToString(", ")}")
            }
        }
    }

    private fun validateAwsCredentials() {
        val awsCredentialsRequired = listOf(
            "AWS_ACCESS_KEY_ID",
            "AWS_SECRET_ACCESS_KEY",
        )
        val missingAwsCredentials = awsCredentialsRequired.filter { environment.getProperty(it).isNullOrBlank() }
        if (missingAwsCredentials.isNotEmpty()) {
            throw IllegalStateException("${CommonErrorCode.BASE_PROPERTIES_INVALID.message}: ${missingAwsCredentials.joinToString(", ")}")
        }
    }
}
