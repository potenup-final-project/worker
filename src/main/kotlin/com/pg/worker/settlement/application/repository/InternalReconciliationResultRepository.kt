package com.pg.worker.settlement.application.repository

import com.pg.worker.settlement.domain.InternalReconciliationResult
import com.pg.worker.settlement.domain.MismatchType
import com.pg.worker.settlement.domain.ReconciliationStatus
import java.time.LocalDate

interface InternalReconciliationResultRepository {
    fun save(result: InternalReconciliationResult): InternalReconciliationResult

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

    fun findFirstOpenMismatchByTransactionId(transactionId: Long): InternalReconciliationResult?
}
