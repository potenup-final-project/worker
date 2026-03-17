package com.pg.worker.settlement.application.service

import com.pg.worker.settlement.application.repository.SettlementReconciliationResultRepository
import com.pg.worker.settlement.domain.ExternalSettlementDetail
import com.pg.worker.settlement.domain.ReconciliationStatus
import com.pg.worker.settlement.domain.SettlementReconciliationResult
import com.pg.worker.settlement.domain.SettlementReconciliationResultType
import com.gop.logging.contract.StructuredLogger
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Component
import java.time.LocalDate

@Component
class SettlementReconciliationWriter(
    private val repository: SettlementReconciliationResultRepository,
    private val log: StructuredLogger) {

    /**
     * MATCHED 결과 저장.
     */
    fun writeMatched(
        reconciliationDate: LocalDate,
        rawDataId: Long?,
        merchantId: Long,
        providerTxId: String,
        externalRecord: ExternalSettlementDetail,
        internalAmount: Long,
    ): Boolean {
        val existing = repository.findByProviderTxIdAndReconciliationDate(providerTxId, reconciliationDate)

        if (existing != null && existing.resultType == SettlementReconciliationResultType.MATCHED) {
            return false
        }

        // 기존에 OPEN mismatch가 있었는데 이번에 MATCHED → RESOLVED 전환
        if (existing != null && existing.status == ReconciliationStatus.OPEN) {
            existing.resolve("재실행 대사 결과 정상 매칭 확인됨")
            repository.save(existing)
            log.info(
                "[외부대사] 기존 OPEN mismatch RESOLVED 전환. providerTxId={}, date={}",
                providerTxId, reconciliationDate
            )
            return true
        }

        return trySave(
            SettlementReconciliationResult.matched(
                reconciliationDate = reconciliationDate,
                merchantId = merchantId,
                providerTxId = providerTxId,
                internalRawDataId = rawDataId,
                externalRecordId = externalRecord.id,
                internalAmount = internalAmount,
                externalAmount = externalRecord.amount,
            )
        )
    }

    /**
     * mismatch 결과 저장.
     */
    fun writeMismatch(
        reconciliationDate: LocalDate,
        providerTxId: String,
        resultType: SettlementReconciliationResultType,
        merchantId: Long?,
        internalRawDataId: Long? = null,
        externalRecordId: Long? = null,
        internalAmount: Long? = null,
        externalAmount: Long? = null,
        reason: String,
    ): Boolean {
        val existing = repository.findByProviderTxIdAndReconciliationDate(providerTxId, reconciliationDate)
        if (existing != null) {
            // 이미 동일한 타입으로 OPEN 상태이면 중복 저장 skip
            if (existing.resultType == resultType && existing.status == ReconciliationStatus.OPEN) {
                return false
            }
            // 타입이 달라졌거나 상태가 달면 업데이트 (실제론 비즈니스 정책에 따라 다름. 여기선 단순화)
        }

        return trySave(
            SettlementReconciliationResult.mismatch(
                reconciliationDate = reconciliationDate,
                merchantId = merchantId,
                providerTxId = providerTxId,
                resultType = resultType,
                internalRawDataId = internalRawDataId,
                externalRecordId = externalRecordId,
                internalAmount = internalAmount,
                externalAmount = externalAmount,
                reason = reason,
            )
        )
    }

    private fun trySave(result: SettlementReconciliationResult): Boolean {
        return try {
            repository.save(result)
            log.info(
                "[외부대사] 결과 저장. providerTxId={}, resultType={}, date={}",
                result.providerTxId, result.resultType, result.reconciliationDate
            )
            true
        } catch (e: DataIntegrityViolationException) {
            log.warn(
                "[외부대사] unique constraint 위반 - 동시성 경합으로 중복 저장 시도 skip. providerTxId={}, date={}",
                result.providerTxId, result.reconciliationDate
            )
            false
        }
    }
}
