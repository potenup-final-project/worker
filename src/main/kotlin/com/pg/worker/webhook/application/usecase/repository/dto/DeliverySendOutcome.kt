package com.pg.worker.webhook.application.usecase.repository.dto

import com.pg.worker.webhook.domain.WebhookDeliveryStatus
import java.time.LocalDateTime

data class DeliverySendOutcome(
    val deliveryId: Long,
    val status: WebhookDeliveryStatus,
    val httpStatus: Int? = null,
    val responseMs: Long? = null,
    val errorCode: String? = null,
    val nextAttemptAt: LocalDateTime? = null,
)
