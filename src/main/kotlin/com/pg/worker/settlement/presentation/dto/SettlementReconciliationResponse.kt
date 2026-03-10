package com.pg.worker.settlement.presentation.dto

import com.pg.worker.settlement.domain.SettlementReconciliationResult
import java.time.LocalDate
import java.time.LocalDateTime

data class SettlementReconciliationResponse(
    val id: Long,
    val reconciliationDate: LocalDate,
    val merchantId: Long?,
    val providerTxId: String,
    val resultType: String,
    val internalRawDataId: Long?,
    val externalRecordId: Long?,
    val internalAmount: Long?,
    val externalAmount: Long?,
    val amountDiff: Long?,
    val status: String,
    val reason: String?,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
) {
    companion object {
        fun from(result: SettlementReconciliationResult) = SettlementReconciliationResponse(
            id = result.id,
            reconciliationDate = result.reconciliationDate,
            merchantId = result.merchantId,
            providerTxId = result.providerTxId,
            resultType = result.resultType.name,
            internalRawDataId = result.internalRawDataId,
            externalRecordId = result.externalRecordId,
            internalAmount = result.internalAmount,
            externalAmount = result.externalAmount,
            amountDiff = result.amountDiff,
            status = result.status.name,
            reason = result.reason,
            createdAt = result.createdAt,
            updatedAt = result.updatedAt,
        )
    }
}
