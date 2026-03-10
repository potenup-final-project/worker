package com.pg.worker.webhook.util

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import com.pg.worker.webhook.domain.WebhookDeliveryStatus
import org.springframework.stereotype.Component

// Webhook delivery BC 관측 메트릭 (success, retry, dead, lease_recovered)
@Component
class WebhookMetrics(private val registry: MeterRegistry) {

    companion object {
        private const val METRIC_LEASE_RECOVERED = "webhook.delivery.lease_recovered"
        private const val METRIC_SUCCESS = "webhook.delivery.success"
        private const val METRIC_RETRY = "webhook.delivery.retry"
        private const val METRIC_DEAD = "webhook.delivery.dead"
        private const val METRIC_DELIVERY_OUTCOME = "webhook.delivery.outcome"
        private const val METRIC_WORKER_LOOP_ERROR = "webhook.worker.loop.error"
        private const val METRIC_LEASE_SWEEP_ERROR = "webhook.lease.sweep.error"

        private const val TAG_RESULT = "result"
        private const val TAG_REASON = "reason"
        private const val TAG_ENDPOINT = "endpoint"
        private const val TAG_EVENT_TYPE = "eventType"

        private const val DEFAULT_REASON = "none"
        private const val DEFAULT_EVENT_TYPE = "unknown"
    }

    private val deliveryLeaseRecovered: Counter = Counter.builder(METRIC_LEASE_RECOVERED)
        .description("deliveries recovered from stuck IN_PROGRESS by lease sweeper")
        .register(registry)

    private val deliverySuccess: Counter = Counter.builder(METRIC_SUCCESS)
        .description("webhook deliveries that succeeded (2xx)")
        .register(registry)

    private val deliveryRetry: Counter = Counter.builder(METRIC_RETRY)
        .description("webhook deliveries that will be retried")
        .register(registry)

    private val deliveryDead: Counter = Counter.builder(METRIC_DEAD)
        .description("webhook deliveries permanently failed (DEAD)")
        .register(registry)

    private val workerLoopError: Counter = Counter.builder(METRIC_WORKER_LOOP_ERROR)
        .description("worker processing loop exceptions")
        .register(registry)

    private val leaseSweepError: Counter = Counter.builder(METRIC_LEASE_SWEEP_ERROR)
        .description("delivery lease sweep exceptions")
        .register(registry)

    fun incrementDeliveryLeaseRecovered(count: Int) = deliveryLeaseRecovered.increment(count.toDouble())

    fun recordDeliverySuccess() = deliverySuccess.increment()

    fun recordDeliveryRetry() = deliveryRetry.increment()

    fun recordDeliveryDead() = deliveryDead.increment()

    fun recordWorkerLoopError() = workerLoopError.increment()

    fun recordLeaseSweepError() = leaseSweepError.increment()

    fun recordDeliveryOutcome(
        status: WebhookDeliveryStatus,
        endpointId: Long,
        reason: String? = null,
        eventType: String? = null,
    ) {
        registry.counter(
            METRIC_DELIVERY_OUTCOME,
            TAG_RESULT,
            mapStatus(status),
            TAG_REASON,
            reason ?: DEFAULT_REASON,
            TAG_ENDPOINT,
            endpointId.toString(),
            TAG_EVENT_TYPE,
            eventType ?: DEFAULT_EVENT_TYPE,
        ).increment()
    }

    private fun mapStatus(status: WebhookDeliveryStatus): String = when (status) {
        WebhookDeliveryStatus.SUCCESS -> "success"
        WebhookDeliveryStatus.FAILED -> "retry"
        WebhookDeliveryStatus.DEAD -> "dead"
        else -> "other"
    }
}
