package com.pg.worker.webhook.reconciliation.application

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "webhook.recon")
data class WebhookReconciliationProperties(
    val enabled: Boolean,
    val chunkSize: Int,
    val maxPages: Int,
    val staleGraceMinutes: Long,
    val staleAgeHours: Long,
    val degradedWindowDays: Long,
    val degradedMinSample: Int,
    val degradedDeadRate: Double,
    val degradedRecoveryWindowDays: Long,
    val missingGraceMinutes: Long,
    val lookbackDays: Long,
    val autoDdl: Boolean,
)
