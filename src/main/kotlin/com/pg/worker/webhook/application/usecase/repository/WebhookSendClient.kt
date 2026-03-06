package com.pg.worker.webhook.application.usecase.repository

import com.pg.worker.webhook.application.usecase.repository.dto.WebhookSendResult
import java.util.UUID

interface WebhookSendClient {
    fun send(url: String, secret: String, eventId: UUID, payloadSnapshot: String): WebhookSendResult
}
