package com.pg.worker.webhook.scheduler

import com.pg.worker.webhook.application.usecase.repository.WebhookDeliveryStateRepository
import com.pg.worker.global.logging.context.TraceScope
import com.pg.worker.webhook.util.WebhookMetrics
import com.gop.logging.contract.StructuredLogger
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

// IN_PROGRESS 상태로 lease 시간 이상 멈춰있는 delivery를 FAILED로 복구하는 스케줄러
@Component
class DeliveryLeaseSweeper(
    private val deliveryRepository: WebhookDeliveryStateRepository,
    private val metrics: WebhookMetrics,
    @Value("\${webhook.lease.minutes}")
    private val leaseMinutes: Int,
    private val log: StructuredLogger) {

    @Scheduled(fixedDelayString = "\${webhook.lease.sweep-interval-ms}", scheduler = "webhookWorkerScheduler")
    fun sweep() {
        val runTraceId = TraceScope.newRunTraceId("worker-webhook-lease")
        TraceScope.withTraceContext(traceId = runTraceId, messageId = "webhook-lease-sweeper") {
            try {
                val recovered = deliveryRepository.recoverExpiredLeases(leaseMinutes)
                if (recovered > 0) {
                    log.warn("[DeliveryLeaseSweeper] lease 만료 복구: {}건 (lease={}분)", recovered, leaseMinutes)
                    metrics.incrementDeliveryLeaseRecovered(recovered)
                }
            } catch (e: Exception) {
                metrics.recordLeaseSweepError()
                log.error("[DeliveryLeaseSweeper] sweep 실패", e)
            }
        }
    }
}
