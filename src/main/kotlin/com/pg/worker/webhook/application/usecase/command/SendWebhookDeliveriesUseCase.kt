package com.pg.worker.webhook.application.usecase.command

interface SendWebhookDeliveriesUseCase {
    fun sendBatch(batchSize: Int)
}