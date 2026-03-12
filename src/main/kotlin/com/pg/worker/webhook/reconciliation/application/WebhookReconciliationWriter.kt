package com.pg.worker.webhook.reconciliation.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.pg.worker.webhook.reconciliation.domain.WebhookMismatchType
import com.pg.worker.webhook.reconciliation.domain.WebhookReconciliationResult
import com.pg.worker.webhook.reconciliation.domain.WebhookReconciliationStatus
import com.pg.worker.webhook.reconciliation.infra.WebhookReconciliationResultStore
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.util.UUID

@Component
@ConditionalOnProperty(prefix = "webhook.recon", name = ["enabled"], havingValue = "true")
class WebhookReconciliationWriter(
    private val store: WebhookReconciliationResultStore,
    private val objectMapper: ObjectMapper,
) {
    fun writeMismatch(
        reconciliationDate: LocalDate,
        merchantId: Long,
        mismatchType: WebhookMismatchType,
        fingerprint: String,
        reason: String,
        eventId: UUID? = null,
        deliveryId: Long? = null,
        endpointId: Long? = null,
        meta: Map<String, Any?>? = null,
    ): Boolean {
        if (store.existsOpenByFingerprint(fingerprint)) {
            return false
        }

        val metaJson = meta?.let { objectMapper.writeValueAsString(it) }
        val result = WebhookReconciliationResult(
            reconciliationDate = reconciliationDate,
            merchantId = merchantId,
            mismatchType = mismatchType,
            status = WebhookReconciliationStatus.OPEN,
            eventId = eventId,
            deliveryId = deliveryId,
            endpointId = endpointId,
            fingerprint = fingerprint,
            reason = reason,
            metaJson = metaJson,
        )
        return store.insertOpen(result)
    }

    fun resolveIfOpen(fingerprint: String, reason: String): Boolean {
        return store.resolveIfOpen(fingerprint, reason) > 0
    }
}
