package com.pg.worker.settlement.application.repository

import com.pg.worker.settlement.domain.ReconciliationStatus
import com.pg.worker.settlement.domain.SettlementReconciliationResult
import com.pg.worker.settlement.domain.SettlementReconciliationResultType
import java.time.LocalDate

interface SettlementReconciliationResultRepository {
    fun save(result: SettlementReconciliationResult): SettlementReconciliationResult

    /** 멱등성 체크: 동일 (providerTxId, reconciliationDate) 조합 존재 여부 */
    fun existsByProviderTxIdAndReconciliationDate(
        providerTxId: String,
        reconciliationDate: LocalDate,
    ): Boolean

    fun findById(id: Long): SettlementReconciliationResult?

    /** Writer에서 기존 OPEN 결과 조회 후 RESOLVED 전환 시 사용 */
    fun findByProviderTxIdAndReconciliationDate(
        providerTxId: String,
        reconciliationDate: LocalDate,
    ): SettlementReconciliationResult?

    /** 조회 API: 날짜 + 선택 필터 */
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
