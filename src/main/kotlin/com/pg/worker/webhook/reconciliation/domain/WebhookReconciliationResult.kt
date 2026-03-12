package com.pg.worker.webhook.reconciliation.domain

import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

data class WebhookReconciliationResult(
    val id: Long = 0,
    val reconciliationDate: LocalDate,
    val merchantId: Long,
    val mismatchType: WebhookMismatchType,
    val eventId: UUID? = null,
    val deliveryId: Long? = null,
    val endpointId: Long? = null,
    val fingerprint: String,
    val reason: String? = null,
    val metaJson: String? = null,
    val status: WebhookReconciliationStatus = WebhookReconciliationStatus.OPEN,
    val createdAt: LocalDateTime = LocalDateTime.now(),
    val updatedAt: LocalDateTime = LocalDateTime.now(),
    val resolvedAt: LocalDateTime? = null,
)
