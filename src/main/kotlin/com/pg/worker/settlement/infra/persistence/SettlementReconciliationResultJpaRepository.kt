package com.pg.worker.settlement.infra.persistence

import com.pg.worker.settlement.domain.ReconciliationStatus
import com.pg.worker.settlement.domain.SettlementReconciliationResult
import com.pg.worker.settlement.domain.SettlementReconciliationResultType
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate

interface SettlementReconciliationResultJpaRepository : JpaRepository<SettlementReconciliationResult, Long> {

    fun existsByProviderTxIdAndReconciliationDate(
        providerTxId: String,
        reconciliationDate: LocalDate,
    ): Boolean

    fun findByProviderTxIdAndReconciliationDate(
        providerTxId: String,
        reconciliationDate: LocalDate,
    ): SettlementReconciliationResult?

    fun findAllByReconciliationDate(reconciliationDate: LocalDate): List<SettlementReconciliationResult>

    fun findAllByReconciliationDateAndMerchantId(
        reconciliationDate: LocalDate,
        merchantId: Long,
    ): List<SettlementReconciliationResult>

    fun findAllByReconciliationDateAndStatus(
        reconciliationDate: LocalDate,
        status: ReconciliationStatus,
    ): List<SettlementReconciliationResult>

    fun findAllByReconciliationDateAndResultType(
        reconciliationDate: LocalDate,
        resultType: SettlementReconciliationResultType,
    ): List<SettlementReconciliationResult>
}
