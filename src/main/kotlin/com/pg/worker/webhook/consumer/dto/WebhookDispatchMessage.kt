package com.pg.worker.webhook.consumer.dto

import java.util.UUID

data class WebhookDispatchMessage(
    val schemaVersion: Int = 1,
    val eventId: UUID,
    val merchantId: Long,
    val payload: String,
)
