package com.pg.worker.settlement.infra.persistence

import com.pg.worker.settlement.application.repository.SettlementReconciliationResultRepository
import com.pg.worker.settlement.domain.ReconciliationStatus
import com.pg.worker.settlement.domain.SettlementReconciliationResult
import com.pg.worker.settlement.domain.SettlementReconciliationResultType
import org.springframework.stereotype.Repository
import java.time.LocalDate

@Repository
class SettlementReconciliationResultRepositoryImpl(
    private val jpaRepository: SettlementReconciliationResultJpaRepository,
) : SettlementReconciliationResultRepository {

    override fun save(result: SettlementReconciliationResult): SettlementReconciliationResult {
        return jpaRepository.save(result)
    }

    override fun existsByProviderTxIdAndReconciliationDate(
        providerTxId: String,
        reconciliationDate: LocalDate,
    ): Boolean {
        return jpaRepository.existsByProviderTxIdAndReconciliationDate(providerTxId, reconciliationDate)
    }

    override fun findByProviderTxIdAndReconciliationDate(
        providerTxId: String,
        reconciliationDate: LocalDate,
    ): SettlementReconciliationResult? {
        return jpaRepository.findByProviderTxIdAndReconciliationDate(providerTxId, reconciliationDate)
    }

    override fun findAllByReconciliationDate(reconciliationDate: LocalDate): List<SettlementReconciliationResult> {
        return jpaRepository.findAllByReconciliationDate(reconciliationDate)
    }

    override fun findAllByReconciliationDateAndMerchantId(
        reconciliationDate: LocalDate,
        merchantId: Long,
    ): List<SettlementReconciliationResult> {
        return jpaRepository.findAllByReconciliationDateAndMerchantId(reconciliationDate, merchantId)
    }

    override fun findAllByReconciliationDateAndStatus(
        reconciliationDate: LocalDate,
        status: ReconciliationStatus,
    ): List<SettlementReconciliationResult> {
        return jpaRepository.findAllByReconciliationDateAndStatus(reconciliationDate, status)
    }

    override fun findAllByReconciliationDateAndResultType(
        reconciliationDate: LocalDate,
        resultType: SettlementReconciliationResultType,
    ): List<SettlementReconciliationResult> {
        return jpaRepository.findAllByReconciliationDateAndResultType(reconciliationDate, resultType)
    }

    override fun findById(id: Long): SettlementReconciliationResult? {
        return jpaRepository.findById(id).orElse(null)
    }
}
