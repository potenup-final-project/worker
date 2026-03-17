package com.pg.worker.settlement.application.service

import com.pg.worker.settlement.application.repository.InternalReconciliationResultRepository
import com.pg.worker.settlement.domain.InternalReconciliationResult
import com.pg.worker.settlement.domain.MismatchType
import com.pg.worker.settlement.domain.PaymentTransaction
import com.pg.worker.settlement.domain.ReconciliationStatus
import com.gop.logging.contract.StructuredLogger
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Component
import java.time.LocalDate

@Component
class InternalReconciliationWriter(
    private val repository: InternalReconciliationResultRepository,
    private val log: StructuredLogger) {

    fun writeMismatch(
        tx: PaymentTransaction,
        date: LocalDate,
        type: MismatchType,
        reason: String
    ): Boolean {
        if (repository.existsByTransactionIdAndMismatchTypeAndStatus(tx.id, type, ReconciliationStatus.OPEN)) {
            return false
        }

        return try {
            val result = InternalReconciliationResult(
                reconciliationDate = date,
                merchantId = tx.merchantId,
                paymentId = tx.paymentId,
                transactionId = tx.id,
                mismatchType = type,
                reason = reason
            )
            repository.save(result)
            log.info("[내부대사] 불일치 생성. tx_id: {}, type: {}, reason: {}", tx.id, type, reason)
            true
        } catch (e: DataIntegrityViolationException) {
            log.warn(
                "[내부대사] DB unique constraint 위반 - 동시성 경합으로 인한 중복 저장 시도로 판단. tx_id: {}, type: {}",
                tx.id, type
            )
            false
        }
    }

    /**
     * 특정 mismatchType의 OPEN 상태인 불일치 건을 찾아 RESOLVED로 변경한다.
     */
    fun resolveIfOpen(transactionId: Long, mismatchType: MismatchType, ledgerId: Long, reason: String): Boolean {
        val openResult = repository.findByTransactionIdAndMismatchTypeAndStatus(
            transactionId, mismatchType, ReconciliationStatus.OPEN
        )
        return openResult?.let {
            it.resolve(ledgerId, reason)
            repository.save(it)
            log.info("[내부대사] 불일치 해결 완료. tx_id: {}, type: {}, ledger_id: {}", transactionId, mismatchType, ledgerId)
            true
        } ?: false
    }

    /**
     * OPEN 상태인 불일치 건의 mismatchType을 변경한다.
     * MISSING_RAW_DATA → MISSING_LEDGER 단계 전환 시 사용.
     */
    fun transitionMismatchType(result: InternalReconciliationResult, newType: MismatchType, reason: String) {
        result.transitionTo(newType, reason)
        repository.save(result)
        log.info("[내부대사] 불일치 유형 전환. tx_id: {}, {} → {}", result.transactionId, result.mismatchType, newType)
    }
}
