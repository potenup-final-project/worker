package com.pg.worker.webhook.reconciliation.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.pg.worker.webhook.reconciliation.domain.WebhookMismatchType
import com.pg.worker.webhook.reconciliation.infra.WebhookReconciliationResultStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

class WebhookReconciliationWriterTest {

    private val store = mockk<WebhookReconciliationResultStore>()
    private val writer = WebhookReconciliationWriter(
        store = store,
        objectMapper = ObjectMapper(),
    )

    @Test
    fun `OPEN fingerprint가 이미 있으면 저장하지 않는다`() {
        every { store.existsOpenByFingerprint("stale:1") } returns true

        val inserted = writer.writeMismatch(
            reconciliationDate = LocalDate.now(),
            merchantId = 9L,
            mismatchType = WebhookMismatchType.STALE_FAILED_DELIVERY,
            fingerprint = "stale:1",
            reason = "stale",
            eventId = UUID.randomUUID(),
            deliveryId = 1L,
        )

        assertEquals(false, inserted)
        verify(exactly = 0) { store.insertOpen(any()) }
    }

    @Test
    fun `OPEN fingerprint가 없으면 mismatch를 저장한다`() {
        every { store.existsOpenByFingerprint("missing:abc:9") } returns false
        every { store.insertOpen(any()) } returns true

        val inserted = writer.writeMismatch(
            reconciliationDate = LocalDate.now(),
            merchantId = 9L,
            mismatchType = WebhookMismatchType.MISSING_DELIVERY,
            fingerprint = "missing:abc:9",
            reason = "missing",
            eventId = UUID.randomUUID(),
        )

        assertEquals(true, inserted)
        verify(exactly = 1) { store.insertOpen(any()) }
    }

    @Test
    fun `동시성 경합으로 insert가 실패하면 false를 반환한다`() {
        every { store.existsOpenByFingerprint("missing:race:9") } returns false
        every { store.insertOpen(any()) } returns false

        val inserted = writer.writeMismatch(
            reconciliationDate = LocalDate.now(),
            merchantId = 9L,
            mismatchType = WebhookMismatchType.MISSING_DELIVERY,
            fingerprint = "missing:race:9",
            reason = "race",
            eventId = UUID.randomUUID(),
        )

        assertEquals(false, inserted)
    }
}
