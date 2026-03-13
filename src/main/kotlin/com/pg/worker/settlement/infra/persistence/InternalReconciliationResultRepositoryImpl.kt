package com.pg.worker.settlement.infra.persistence

import com.pg.worker.settlement.application.repository.InternalReconciliationResultRepository
import com.pg.worker.settlement.domain.InternalReconciliationResult
import com.pg.worker.settlement.domain.MismatchType
import com.pg.worker.settlement.domain.ReconciliationStatus
import org.springframework.stereotype.Repository
import java.time.LocalDate

@Repository
class InternalReconciliationResultRepositoryImpl(
    private val jpaRepository: InternalReconciliationResultJpaRepository
) : InternalReconciliationResultRepository {

    override fun save(result: InternalReconciliationResult): InternalReconciliationResult {
        return jpaRepository.save(result)
    }

    override fun findByTransactionIdAndMismatchTypeAndStatus(
        transactionId: Long,
        mismatchType: MismatchType,
        status: ReconciliationStatus
    ): InternalReconciliationResult? {
        return jpaRepository.findByTransactionIdAndMismatchTypeAndStatus(transactionId, mismatchType, status)
    }

    override fun existsByTransactionIdAndMismatchTypeAndStatus(
        transactionId: Long,
        mismatchType: MismatchType,
        status: ReconciliationStatus
    ): Boolean {
        return jpaRepository.existsByTransactionIdAndMismatchTypeAndStatus(transactionId, mismatchType, status)
    }

    override fun findAllByStatusAndMismatchTypeInAndReconciliationDateAfter(
        status: ReconciliationStatus,
        mismatchTypes: List<MismatchType>,
        date: LocalDate
    ): List<InternalReconciliationResult> {
        return jpaRepository.findAllByStatusAndMismatchTypeInAndReconciliationDateAfter(status, mismatchTypes, date)
    }

    override fun findFirstOpenMismatchByTransactionId(transactionId: Long): InternalReconciliationResult? {
        return jpaRepository.findFirstByTransactionIdAndStatusOrderByCreatedAtAsc(transactionId, ReconciliationStatus.OPEN)
    }
}
