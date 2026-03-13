package com.pg.worker.settlement.infra.persistence

import com.pg.worker.settlement.application.repository.SettlementReconciliationResultRepository
import com.pg.worker.settlement.domain.SettlementReconciliationResult
import org.springframework.stereotype.Repository
import java.time.LocalDate

@Repository
class SettlementReconciliationResultRepositoryImpl(
    private val jpaRepository: SettlementReconciliationResultJpaRepository,
) : SettlementReconciliationResultRepository {

    override fun save(result: SettlementReconciliationResult): SettlementReconciliationResult {
        return jpaRepository.save(result)
    }

    override fun findByProviderTxIdAndReconciliationDate(
        providerTxId: String,
        reconciliationDate: LocalDate,
    ): SettlementReconciliationResult? {
        return jpaRepository.findByProviderTxIdAndReconciliationDate(providerTxId, reconciliationDate)
    }

    override fun findById(id: Long): SettlementReconciliationResult? {
        return jpaRepository.findById(id).orElse(null)
    }
}
