package com.pg.worker.webhook.domain

enum class WebhookDeliveryStatus {
    READY,
    IN_PROGRESS,
    SUCCESS,
    FAILED,
    DEAD,
}