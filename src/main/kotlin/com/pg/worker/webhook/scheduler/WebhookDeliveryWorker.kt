package com.pg.worker.webhook.scheduler

import com.pg.worker.webhook.application.usecase.command.SendWebhookDeliveriesUseCase
import com.pg.worker.global.logging.context.TraceScope
import com.pg.worker.webhook.util.WebhookMetrics
import com.gop.logging.contract.StructuredLogger
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class WebhookDeliveryWorker(
    private val sendWebhookDeliveriesUseCase: SendWebhookDeliveriesUseCase,
    private val metrics: WebhookMetrics,
    @Value("\${webhook.worker.batch-size}") private val batchSize: Int,
    private val log: StructuredLogger) {

    @Scheduled(fixedDelayString = "\${webhook.worker.interval-ms}", scheduler = "webhookWorkerScheduler")
    fun process() {
        val runTraceId = TraceScope.newRunTraceId("worker-webhook-delivery")
        TraceScope.withTraceContext(traceId = runTraceId, messageId = "webhook-delivery-worker") {
            try {
                sendWebhookDeliveriesUseCase.sendBatch(batchSize)
            } catch (e: Exception) {
                metrics.recordWorkerLoopError()
                log.error("[WebhookDeliveryWorker] process 루프 예외", e)
            }
        }
    }
}
