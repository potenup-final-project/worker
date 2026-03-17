package com.pg.worker.webhook.reconciliation.application

import com.pg.worker.webhook.domain.WebhookDeliveryStatus
import com.pg.worker.webhook.reconciliation.domain.WebhookMismatchType
import com.pg.worker.webhook.reconciliation.domain.WebhookReconciliationResult
import com.pg.worker.webhook.reconciliation.infra.PgCoreOutboxReadRepository
import com.pg.worker.webhook.reconciliation.infra.WebhookDeliveryReconciliationReader
import com.pg.worker.webhook.reconciliation.infra.WebhookReconciliationResultStore
import io.micrometer.core.instrument.MeterRegistry
import com.gop.logging.contract.StructuredLogger
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicLong

@Service
@ConditionalOnProperty(prefix = "webhook.recon", name = ["enabled"], havingValue = "true")
class WebhookReconciliationService(
    private val writer: WebhookReconciliationWriter,
    private val resultStore: WebhookReconciliationResultStore,
    private val deliveryReader: WebhookDeliveryReconciliationReader,
    private val outboxReadRepository: PgCoreOutboxReadRepository,
    private val meterRegistry: MeterRegistry,
    private val props: WebhookReconciliationProperties,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val log: StructuredLogger) {
    private val openGaugeByType: Map<WebhookMismatchType, AtomicLong> = WebhookMismatchType.entries.associateWith { type ->
        AtomicLong(0L).also { ref ->
            meterRegistry.gauge("webhook.recon.open.count", listOf(io.micrometer.core.instrument.Tag.of("type", type.name)), ref)
        }
    }

    fun detectStaleDeliveries(targetDate: LocalDate) {
        log.info("[Reconciliation] detectStaleDeliveries start targetDate={}", targetDate)
        var detectedCount = 0
        val graceThreshold = LocalDateTime.now(clock).minusMinutes(props.staleGraceMinutes)
        val ageThreshold = LocalDateTime.now(clock).minusHours(props.staleAgeHours)

        processInChunks(fetcher = { offset, limit ->
            deliveryReader.findStaleFailedDeliveries(
                graceThreshold = graceThreshold,
                ageThreshold = ageThreshold,
                offset = offset,
                limit = limit,
            )
        }) { chunk ->
            chunk.forEach { delivery ->
                val inserted = writer.writeMismatch(
                    reconciliationDate = targetDate,
                    merchantId = delivery.merchantId,
                    mismatchType = WebhookMismatchType.STALE_FAILED_DELIVERY,
                    fingerprint = "stale:${delivery.deliveryId}",
                    reason = "FAILED 상태 장기 체류",
                    eventId = delivery.eventId,
                    deliveryId = delivery.deliveryId,
                    endpointId = delivery.endpointId,
                    meta = mapOf(
                        "attemptNo" to delivery.attemptNo,
                        "lastError" to delivery.lastError,
                    ),
                )
                if (inserted) {
                    detectedCount++
                    incrementDetected(WebhookMismatchType.STALE_FAILED_DELIVERY)
                }
            }
        }
        log.info("[Reconciliation] detectStaleDeliveries done targetDate={} detected={}", targetDate, detectedCount)
    }

    fun detectDegradedEndpoints(targetDate: LocalDate) {
        log.info("[Reconciliation] detectDegradedEndpoints start targetDate={}", targetDate)
        var detectedCount = 0
        val since = targetDate.minusDays(props.degradedWindowDays)
        processInChunks(fetcher = { offset, limit ->
            deliveryReader.aggregateEndpointStats(
                since = since,
                minSample = props.degradedMinSample,
                offset = offset,
                limit = limit,
            )
        }) { chunk ->
            chunk.filter { it.deadRate >= props.degradedDeadRate }
                .forEach { stats ->
                    val fingerprint = "degraded:${stats.merchantId}:${stats.endpointId}:${since}:${targetDate}"
                    val inserted = writer.writeMismatch(
                        reconciliationDate = targetDate,
                        merchantId = stats.merchantId,
                        mismatchType = WebhookMismatchType.ENDPOINT_DEGRADED,
                        fingerprint = fingerprint,
                        reason = "최근 ${props.degradedWindowDays}일 실패율 ${"%.3f".format(stats.deadRate)}",
                        endpointId = stats.endpointId,
                        meta = mapOf(
                            "total" to stats.total,
                            "dead" to stats.deadCount,
                            "success" to stats.successCount,
                            "deadRate" to stats.deadRate,
                            "windowStart" to since,
                            "windowEnd" to targetDate,
                        ),
                    )
                    if (inserted) {
                        detectedCount++
                        incrementDetected(WebhookMismatchType.ENDPOINT_DEGRADED)
                    }
                }
        }
        log.info("[Reconciliation] detectDegradedEndpoints done targetDate={} detected={}", targetDate, detectedCount)
    }

    fun detectMissingDeliveries(targetDate: LocalDate) {
        log.info("[Reconciliation] detectMissingDeliveries start targetDate={}", targetDate)
        var detectedCount = 0
        val cutoff = targetDate.plusDays(1).atStartOfDay().minusMinutes(props.missingGraceMinutes)

        processInChunks(fetcher = { offset, limit ->
            outboxReadRepository.findPublishedEventsByDate(
                targetDate = targetDate,
                cutoff = cutoff,
                offset = offset,
                limit = limit,
            )
        }) { chunk ->
            val eventIds = chunk.map { it.eventId }
            val existingEventIds = deliveryReader.findExistingEventIds(eventIds)
            val merchantsWithActiveEndpoints = deliveryReader.findMerchantsWithActiveEndpoints(
                chunk.map { it.merchantId }.distinct(),
            )

            chunk.filter { it.eventId !in existingEventIds }
                .filter { it.merchantId in merchantsWithActiveEndpoints }
                .forEach { event ->
                    val inserted = writer.writeMismatch(
                        reconciliationDate = targetDate,
                        merchantId = event.merchantId,
                        mismatchType = WebhookMismatchType.MISSING_DELIVERY,
                        fingerprint = "missing:${event.eventId}:${event.merchantId}",
                        reason = "이벤트 발행 후 delivery 미생성",
                        eventId = event.eventId,
                        meta = mapOf(
                            "eventType" to event.eventType,
                        ),
                    )
                    if (inserted) {
                        detectedCount++
                        incrementDetected(WebhookMismatchType.MISSING_DELIVERY)
                    }
                }
        }
        log.info("[Reconciliation] detectMissingDeliveries done targetDate={} detected={}", targetDate, detectedCount)
    }

    fun resolveOpenMismatches() {
        log.info("[Reconciliation] resolveOpenMismatches start")
        val lookBackDate = LocalDate.now(clock).minusDays(props.lookbackDays)
        val resolvedMissingAndStale = resolveMissingAndStale(lookBackDate)
        val resolvedDegraded = resolveRecoveredEndpoints(lookBackDate)

        updateOpenGaugeCounts()
        log.info(
            "[Reconciliation] resolveOpenMismatches done resolvedMissingAndStale={} resolvedDegraded={} totalResolved={}",
            resolvedMissingAndStale,
            resolvedDegraded,
            resolvedMissingAndStale + resolvedDegraded,
        )
    }

    private fun resolveMissingAndStale(lookBackDate: LocalDate): Int {
        var resolvedCount = 0
        processOpenInChunks(
            types = listOf(
                WebhookMismatchType.MISSING_DELIVERY,
                WebhookMismatchType.STALE_FAILED_DELIVERY,
            ),
            since = lookBackDate,
        ) { openResults ->
            val existingEventIds = deliveryReader.findExistingEventIds(openResults.mapNotNull { it.eventId })
            val deliveryStatusMap = deliveryReader.findStatusByIds(openResults.mapNotNull { it.deliveryId })

            openResults.forEach { result ->
                when (result.mismatchType) {
                    WebhookMismatchType.MISSING_DELIVERY -> {
                        if (result.eventId != null && result.eventId in existingEventIds) {
                            if (writer.resolveIfOpen(result.fingerprint, "재검사: delivery 생성 확인됨")) {
                                resolvedCount++
                                incrementResolved(WebhookMismatchType.MISSING_DELIVERY)
                            }
                        }
                    }

                    WebhookMismatchType.STALE_FAILED_DELIVERY -> {
                        val status = result.deliveryId?.let { deliveryStatusMap[it] }
                        if (status in TERMINAL_STATUSES) {
                            if (writer.resolveIfOpen(result.fingerprint, "재검사: 최종 상태 도달 ($status)")) {
                                resolvedCount++
                                incrementResolved(WebhookMismatchType.STALE_FAILED_DELIVERY)
                            }
                        }
                    }

                    else -> Unit
                }
            }
        }
        return resolvedCount
    }

    private fun resolveRecoveredEndpoints(lookBackDate: LocalDate): Int {
        var resolvedCount = 0
        processOpenInChunks(
            types = listOf(WebhookMismatchType.ENDPOINT_DEGRADED),
            since = lookBackDate,
        ) { openResults ->
            val endpointIds = openResults.mapNotNull { it.endpointId }.distinct()
            val statsMap = deliveryReader.aggregateEndpointStatsByIds(
                endpointIds = endpointIds,
                since = LocalDate.now(clock).minusDays(props.degradedRecoveryWindowDays),
            )

            openResults.forEach { result ->
                val endpointId = result.endpointId ?: return@forEach
                val stats = statsMap[endpointId] ?: return@forEach
                if (stats.total >= props.degradedMinSample && stats.deadRate < props.degradedDeadRate) {
                    if (writer.resolveIfOpen(result.fingerprint, "재검사: deadRate=${"%.3f".format(stats.deadRate)}로 회복됨")) {
                        resolvedCount++
                        incrementResolved(WebhookMismatchType.ENDPOINT_DEGRADED)
                    }
                }
            }
        }
        return resolvedCount
    }

    fun updateOpenGaugeCounts() {
        WebhookMismatchType.entries.forEach { type ->
            val count = resultStore.countOpenByType(type)
            openGaugeByType[type]?.set(count)
        }
    }

    private fun <T> processInChunks(
        fetcher: (offset: Int, limit: Int) -> List<T>,
        processor: (List<T>) -> Unit,
    ) {
        var offset = 0
        var page = 0
        do {
            val chunk = fetcher(offset, props.chunkSize)
            if (chunk.isEmpty()) break
            processor(chunk)
            offset += chunk.size
            page++
        } while (chunk.size == props.chunkSize && page < props.maxPages)

        if (page >= props.maxPages) {
            log.warn("[WebhookReconciliationService] max pages reached maxPages={} chunkSize={}", props.maxPages, props.chunkSize)
        }
    }

    private fun processOpenInChunks(
        types: List<WebhookMismatchType>,
        since: LocalDate,
        processor: (List<WebhookReconciliationResult>) -> Unit,
    ) {
        processInChunks(fetcher = { offset, limit ->
            resultStore.findOpenByTypesSince(types, since, offset, limit)
        }, processor = processor)
    }

    private fun incrementDetected(type: WebhookMismatchType) {
        meterRegistry.counter("webhook.recon.detected", "type", type.name).increment()
    }

    private fun incrementResolved(type: WebhookMismatchType) {
        meterRegistry.counter("webhook.recon.resolved", "type", type.name).increment()
    }

    companion object {
        private val TERMINAL_STATUSES = setOf(WebhookDeliveryStatus.SUCCESS, WebhookDeliveryStatus.DEAD)
    }
}
