package com.pg.worker.settlement.infra.persistence

import com.pg.worker.settlement.domain.InternalReconciliationResult
import com.pg.worker.settlement.domain.MismatchType
import com.pg.worker.settlement.domain.ReconciliationStatus
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate

interface InternalReconciliationResultJpaRepository : JpaRepository<InternalReconciliationResult, Long> {
    fun findByTransactionIdAndMismatchTypeAndStatus(
        transactionId: Long,
        mismatchType: MismatchType,
        status: ReconciliationStatus
    ): InternalReconciliationResult?

    fun existsByTransactionIdAndMismatchTypeAndStatus(
        transactionId: Long,
        mismatchType: MismatchType,
        status: ReconciliationStatus
    ): Boolean

    fun findAllByStatusAndMismatchTypeInAndReconciliationDateAfter(
        status: ReconciliationStatus,
        mismatchTypes: List<MismatchType>,
        date: LocalDate
    ): List<InternalReconciliationResult>
}
