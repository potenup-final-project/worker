package com.pg.worker.webhook.reconciliation.application

import com.pg.worker.webhook.reconciliation.domain.EndpointStatsProjection
import com.pg.worker.webhook.reconciliation.domain.PublishedOutboxEvent
import com.pg.worker.webhook.reconciliation.domain.StaleDeliveryProjection
import com.pg.worker.webhook.reconciliation.domain.WebhookMismatchType
import com.pg.worker.webhook.reconciliation.domain.WebhookReconciliationResult
import com.pg.worker.webhook.reconciliation.domain.WebhookReconciliationStatus
import com.pg.worker.webhook.domain.WebhookDeliveryStatus
import com.pg.worker.webhook.reconciliation.infra.PgCoreOutboxReadRepository
import com.pg.worker.webhook.reconciliation.infra.WebhookDeliveryReconciliationReader
import com.pg.worker.webhook.reconciliation.infra.WebhookReconciliationResultStore
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

class WebhookReconciliationServiceTest {

    private val writer = mockk<WebhookReconciliationWriter>()
    private val resultStore = mockk<WebhookReconciliationResultStore>()
    private val deliveryReader = mockk<WebhookDeliveryReconciliationReader>()
    private val outboxReadRepository = mockk<PgCoreOutboxReadRepository>()
    private val meterRegistry = SimpleMeterRegistry()
    private val fixedClock = Clock.fixed(Instant.parse("2026-03-10T10:00:00Z"), ZoneOffset.UTC)

    private val props = WebhookReconciliationProperties(
        enabled = true,
        chunkSize = 1000,
        maxPages = 100,
        staleGraceMinutes = 60,
        staleAgeHours = 24,
        degradedWindowDays = 7,
        degradedMinSample = 30,
        degradedDeadRate = 0.8,
        degradedRecoveryWindowDays = 3,
        missingGraceMinutes = 30,
        lookbackDays = 30,
        autoDdl = false,
    )

    private fun service() = WebhookReconciliationService(
        writer = writer,
        resultStore = resultStore,
        deliveryReader = deliveryReader,
        outboxReadRepository = outboxReadRepository,
        meterRegistry = meterRegistry,
        props = props,
        clock = fixedClock,
    )

    @Test
    fun `detectStaleDeliveries는 clock 기준 임계값과 구조화된 meta를 사용한다`() {
        val graceThreshold = slot<LocalDateTime>()
        val ageThreshold = slot<LocalDateTime>()
        every {
            deliveryReader.findStaleFailedDeliveries(
                graceThreshold = capture(graceThreshold),
                ageThreshold = capture(ageThreshold),
                offset = any(),
                limit = any(),
            )
        } returns listOf(
            StaleDeliveryProjection(
                deliveryId = 10L,
                endpointId = 20L,
                merchantId = 9L,
                eventId = UUID.randomUUID(),
                attemptNo = 2,
                lastError = "HTTP_500",
            )
        ) andThen emptyList()
        every { writer.writeMismatch(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns true

        service().detectStaleDeliveries(LocalDate.of(2026, 3, 9))

        assertEquals(LocalDateTime.of(2026, 3, 10, 9, 0), graceThreshold.captured)
        assertEquals(LocalDateTime.of(2026, 3, 9, 10, 0), ageThreshold.captured)
        verify(exactly = 1) {
            writer.writeMismatch(
                reconciliationDate = LocalDate.of(2026, 3, 9),
                merchantId = 9L,
                mismatchType = WebhookMismatchType.STALE_FAILED_DELIVERY,
                fingerprint = "stale:10",
                reason = "FAILED 상태 장기 체류",
                eventId = any(),
                deliveryId = 10L,
                endpointId = 20L,
                meta = match { it["attemptNo"] == 2 && it["lastError"] == "HTTP_500" },
            )
        }
    }

    @Test
    fun `detectMissingDeliveries는 활성 endpoint merchant만 대상으로 처리한다`() {
        val e1 = PublishedOutboxEvent(UUID.randomUUID(), 1L, "PAYMENT_DONE")
        val e2 = PublishedOutboxEvent(UUID.randomUUID(), 2L, "PAYMENT_DONE")

        every { outboxReadRepository.findPublishedEventsByDate(any(), any(), any(), any()) } returns listOf(e1, e2) andThen emptyList()
        every { deliveryReader.findExistingEventIds(any()) } returns emptySet()
        every { deliveryReader.findMerchantsWithActiveEndpoints(any()) } returns setOf(1L)
        every { writer.writeMismatch(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns true

        service().detectMissingDeliveries(LocalDate.of(2026, 3, 9))

        verify(exactly = 1) {
            writer.writeMismatch(
                reconciliationDate = LocalDate.of(2026, 3, 9),
                merchantId = 1L,
                mismatchType = WebhookMismatchType.MISSING_DELIVERY,
                fingerprint = "missing:${e1.eventId}:1",
                reason = "이벤트 발행 후 delivery 미생성",
                eventId = e1.eventId,
                deliveryId = null,
                endpointId = null,
                meta = match { it["eventType"] == "PAYMENT_DONE" },
            )
        }
        verify(exactly = 0) {
            writer.writeMismatch(
                reconciliationDate = LocalDate.of(2026, 3, 9),
                merchantId = 2L,
                mismatchType = WebhookMismatchType.MISSING_DELIVERY,
                fingerprint = any(),
                reason = any(),
                eventId = any(),
                deliveryId = any(),
                endpointId = any(),
                meta = any(),
            )
        }
    }

    @Test
    fun `resolveOpenMismatches는 ENDPOINT_DEGRADED 해소 시 최소 표본수를 만족해야 한다`() {
        val degraded = WebhookReconciliationResult(
            id = 1L,
            reconciliationDate = LocalDate.of(2026, 3, 9),
            merchantId = 9L,
            mismatchType = WebhookMismatchType.ENDPOINT_DEGRADED,
            status = WebhookReconciliationStatus.OPEN,
            endpointId = 77L,
            fingerprint = "degraded:9:77:2026-03-02:2026-03-09",
        )

        every {
            resultStore.findOpenByTypesSince(
                types = listOf(WebhookMismatchType.MISSING_DELIVERY, WebhookMismatchType.STALE_FAILED_DELIVERY),
                since = any(),
                offset = any(),
                limit = any(),
            )
        } returns emptyList()
        every {
            resultStore.findOpenByTypesSince(
                types = listOf(WebhookMismatchType.ENDPOINT_DEGRADED),
                since = any(),
                offset = any(),
                limit = any(),
            )
        } returns listOf(degraded) andThen emptyList()

        every { deliveryReader.findExistingEventIds(any()) } returns emptySet()
        every { deliveryReader.findStatusByIds(any()) } returns emptyMap()
        every { deliveryReader.aggregateEndpointStatsByIds(any(), any()) } returns mapOf(
            77L to EndpointStatsProjection(
                endpointId = 77L,
                merchantId = 9L,
                total = 10L,
                deadCount = 1L,
                successCount = 9L,
            )
        )
        every { resultStore.countOpenByType(any()) } returns 0L

        service().resolveOpenMismatches()

        verify(exactly = 0) { writer.resolveIfOpen(any(), any()) }
    }

    @Test
    fun `resolveOpenMismatches는 MISSING_DELIVERY 조건을 만족하면 해소한다`() {
        val eventId = UUID.randomUUID()
        val missing = WebhookReconciliationResult(
            id = 2L,
            reconciliationDate = LocalDate.of(2026, 3, 9),
            merchantId = 9L,
            mismatchType = WebhookMismatchType.MISSING_DELIVERY,
            status = WebhookReconciliationStatus.OPEN,
            eventId = eventId,
            fingerprint = "missing:$eventId:9",
        )

        every {
            resultStore.findOpenByTypesSince(
                types = listOf(WebhookMismatchType.MISSING_DELIVERY, WebhookMismatchType.STALE_FAILED_DELIVERY),
                since = any(),
                offset = any(),
                limit = any(),
            )
        } returns listOf(missing) andThen emptyList()
        every {
            resultStore.findOpenByTypesSince(
                types = listOf(WebhookMismatchType.ENDPOINT_DEGRADED),
                since = any(),
                offset = any(),
                limit = any(),
            )
        } returns emptyList()

        every { deliveryReader.findExistingEventIds(listOf(eventId)) } returns setOf(eventId)
        every { deliveryReader.findStatusByIds(any()) } returns emptyMap()
        every { writer.resolveIfOpen(missing.fingerprint, any()) } returns true
        every { resultStore.countOpenByType(any()) } returns 0L

        service().resolveOpenMismatches()

        verify(exactly = 1) { writer.resolveIfOpen(missing.fingerprint, "재검사: delivery 생성 확인됨") }
    }

    @Test
    fun `resolveOpenMismatches는 STALE_FAILED_DELIVERY가 최종 상태면 해소한다`() {
        val stale = WebhookReconciliationResult(
            id = 3L,
            reconciliationDate = LocalDate.of(2026, 3, 9),
            merchantId = 9L,
            mismatchType = WebhookMismatchType.STALE_FAILED_DELIVERY,
            status = WebhookReconciliationStatus.OPEN,
            deliveryId = 55L,
            fingerprint = "stale:55",
        )

        every {
            resultStore.findOpenByTypesSince(
                types = listOf(WebhookMismatchType.MISSING_DELIVERY, WebhookMismatchType.STALE_FAILED_DELIVERY),
                since = any(),
                offset = any(),
                limit = any(),
            )
        } returns listOf(stale) andThen emptyList()
        every {
            resultStore.findOpenByTypesSince(
                types = listOf(WebhookMismatchType.ENDPOINT_DEGRADED),
                since = any(),
                offset = any(),
                limit = any(),
            )
        } returns emptyList()

        every { deliveryReader.findExistingEventIds(any()) } returns emptySet()
        every { deliveryReader.findStatusByIds(listOf(55L)) } returns mapOf(55L to WebhookDeliveryStatus.DEAD)
        every { writer.resolveIfOpen(stale.fingerprint, any()) } returns true
        every { resultStore.countOpenByType(any()) } returns 0L

        service().resolveOpenMismatches()

        verify(exactly = 1) { writer.resolveIfOpen(stale.fingerprint, "재검사: 최종 상태 도달 (DEAD)") }
    }

    @Test
    fun `resolveOpenMismatches는 ENDPOINT_DEGRADED가 회복되면 해소한다`() {
        val degraded = WebhookReconciliationResult(
            id = 4L,
            reconciliationDate = LocalDate.of(2026, 3, 9),
            merchantId = 9L,
            mismatchType = WebhookMismatchType.ENDPOINT_DEGRADED,
            status = WebhookReconciliationStatus.OPEN,
            endpointId = 77L,
            fingerprint = "degraded:9:77:2026-03-02:2026-03-09",
        )

        every {
            resultStore.findOpenByTypesSince(
                types = listOf(WebhookMismatchType.MISSING_DELIVERY, WebhookMismatchType.STALE_FAILED_DELIVERY),
                since = any(),
                offset = any(),
                limit = any(),
            )
        } returns emptyList()
        every {
            resultStore.findOpenByTypesSince(
                types = listOf(WebhookMismatchType.ENDPOINT_DEGRADED),
                since = any(),
                offset = any(),
                limit = any(),
            )
        } returns listOf(degraded) andThen emptyList()

        every { deliveryReader.findExistingEventIds(any()) } returns emptySet()
        every { deliveryReader.findStatusByIds(any()) } returns emptyMap()
        every { deliveryReader.aggregateEndpointStatsByIds(any(), any()) } returns mapOf(
            77L to EndpointStatsProjection(
                endpointId = 77L,
                merchantId = 9L,
                total = 100L,
                deadCount = 20L,
                successCount = 80L,
            )
        )
        every { writer.resolveIfOpen(degraded.fingerprint, any()) } returns true
        every { resultStore.countOpenByType(any()) } returns 0L

        service().resolveOpenMismatches()

        verify(exactly = 1) {
            writer.resolveIfOpen(
                degraded.fingerprint,
                match { it.startsWith("재검사: deadRate=") && it.endsWith("로 회복됨") },
            )
        }
    }
}
