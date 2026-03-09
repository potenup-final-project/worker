package com.pg.worker.webhook.consumer.dto

import java.util.UUID

data class WebhookDispatchMessage(
    val schemaVersion: Int = 1,
    val messageId: String? = null,
    val traceId: String? = null,
    val occurredAt: String? = null,
    val eventType: String? = null,
    val eventId: UUID,
    val merchantId: Long,
    val payload: String,
)
