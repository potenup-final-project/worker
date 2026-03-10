package com.pg.worker.webhook.reconciliation.domain

enum class WebhookMismatchType {
    MISSING_DELIVERY,
    STALE_FAILED_DELIVERY,
    ENDPOINT_DEGRADED,
}
