package com.pg.worker.webhook.infra.messaging.consumer.dto

import java.util.UUID

data class WebhookDispatchMessage(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val messageId: String? = null,
    val traceId: String? = null,
    val occurredAt: String? = null,
    val eventType: String? = null,
    val eventId: UUID,
    val merchantId: Long,
    val payload: String,
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}
