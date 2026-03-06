package com.pg.worker.webhook.scheduler

import com.pg.worker.webhook.application.usecase.repository.WebhookDeliveryStateRepository
import com.pg.worker.webhook.util.WebhookMetrics
import org.slf4j.LoggerFactory
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
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${webhook.lease.sweep-interval-ms}")
    fun sweep() {
        try {
            val recovered = deliveryRepository.recoverExpiredLeases(leaseMinutes)
            if (recovered > 0) {
                log.warn("[DeliveryLeaseSweeper] lease 만료 복구: {}건 (lease={}분)", recovered, leaseMinutes)
                metrics.incrementDeliveryLeaseRecovered(recovered)
            }
        } catch (e: Exception) {
            log.error("[DeliveryLeaseSweeper] sweep 실패", e)
        }
    }
}