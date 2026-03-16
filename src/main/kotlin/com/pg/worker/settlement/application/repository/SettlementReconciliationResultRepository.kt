package com.pg.worker.settlement.application.repository

import com.pg.worker.settlement.domain.ReconciliationStatus
import com.pg.worker.settlement.domain.SettlementReconciliationResult
import com.pg.worker.settlement.domain.SettlementReconciliationResultType
import java.time.LocalDate

interface SettlementReconciliationResultRepository {
    fun save(result: SettlementReconciliationResult): SettlementReconciliationResult

    fun findById(id: Long): SettlementReconciliationResult?

    /** Writer에서 기존 OPEN 결과 조회 후 RESOLVED 전환 시 사용 */
    fun findByProviderTxIdAndReconciliationDate(
        providerTxId: String,
        reconciliationDate: LocalDate,
    ): SettlementReconciliationResult?
}
