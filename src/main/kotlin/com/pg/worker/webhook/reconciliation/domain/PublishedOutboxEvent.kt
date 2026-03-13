package com.pg.worker.webhook.reconciliation.domain

import java.util.UUID

data class PublishedOutboxEvent(
    val eventId: UUID,
    val merchantId: Long,
    val eventType: String,
)
