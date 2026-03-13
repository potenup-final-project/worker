package com.pg.worker.webhook.reconciliation.domain

import java.util.UUID

data class StaleDeliveryProjection(
    val deliveryId: Long,
    val endpointId: Long,
    val merchantId: Long,
    val eventId: UUID,
    val attemptNo: Int,
    val lastError: String?,
)
