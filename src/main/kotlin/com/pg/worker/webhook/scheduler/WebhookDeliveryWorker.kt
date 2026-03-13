package com.pg.worker.webhook.scheduler

import com.pg.worker.webhook.application.usecase.command.SendWebhookDeliveriesUseCase
import com.pg.worker.webhook.util.WebhookMetrics
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class WebhookDeliveryWorker(
    private val sendWebhookDeliveriesUseCase: SendWebhookDeliveriesUseCase,
    private val metrics: WebhookMetrics,
    @Value("\${webhook.worker.batch-size}") private val batchSize: Int,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${webhook.worker.interval-ms}", scheduler = "webhookWorkerScheduler")
    fun process() {
        try {
            sendWebhookDeliveriesUseCase.sendBatch(batchSize)
        } catch (e: Exception) {
            metrics.recordWorkerLoopError()
            log.error("[WebhookDeliveryWorker] process 루프 예외", e)
        }
    }
}
