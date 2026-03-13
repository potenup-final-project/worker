package com.pg.worker.settlement.infra.persistence

import com.pg.worker.settlement.domain.SettlementReconciliationResult
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate

interface SettlementReconciliationResultJpaRepository : JpaRepository<SettlementReconciliationResult, Long> {
    fun findByProviderTxIdAndReconciliationDate(
        providerTxId: String,
        reconciliationDate: LocalDate,
    ): SettlementReconciliationResult?
}
