package com.pg.worker.settlement.application.service

import com.pg.worker.settlement.application.repository.SettlementReconciliationResultRepository
import com.pg.worker.settlement.domain.ExternalTransactionRecord
import com.pg.worker.settlement.domain.ReconciliationStatus
import com.pg.worker.settlement.domain.SettlementRawData
import com.pg.worker.settlement.domain.SettlementReconciliationResult
import com.pg.worker.settlement.domain.SettlementReconciliationResultType
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Component
import java.time.LocalDate

@Component
class SettlementReconciliationWriter(
    private val repository: SettlementReconciliationResultRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * MATCHED 결과 저장.
     * 이미 해당 날짜에 동일 providerTxId 결과가 존재하면 skip.
     * 기존 OPEN 결과가 있었다면 RESOLVED로 전환.
     */
    fun writeMatched(
        reconciliationDate: LocalDate,
        rawData: SettlementRawData,
        externalRecord: ExternalTransactionRecord,
    ): Boolean {
        val existing = repository.findByProviderTxIdAndReconciliationDate(rawData.providerTxId, reconciliationDate)

        if (existing != null && existing.resultType == SettlementReconciliationResultType.MATCHED) {
            return false
        }

        // 기존에 OPEN mismatch가 있었는데 이번에 MATCHED → RESOLVED 전환
        if (existing != null && existing.status == ReconciliationStatus.OPEN) {
            existing.resolve("재실행 대사 결과 정상 매칭 확인됨")
            repository.save(existing)
            log.info(
                "[외부대사] 기존 OPEN mismatch RESOLVED 전환. providerTxId={}, date={}",
                rawData.providerTxId, reconciliationDate
            )
            return true
        }

        return trySave(
            SettlementReconciliationResult.matched(
                reconciliationDate = reconciliationDate,
                merchantId = rawData.merchantId,
                providerTxId = rawData.providerTxId,
                internalRawDataId = rawData.id,
                externalRecordId = externalRecord.id,
                internalAmount = rawData.amount,
                externalAmount = externalRecord.amount,
            )
        )
    }

    /**
     * mismatch 결과 저장 (MATCHED 외 모든 resultType).
     * 동일 (providerTxId, reconciliationDate) 조합이 이미 OPEN 상태이면 중복 저장 skip.
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
        if (repository.existsByProviderTxIdAndReconciliationDate(providerTxId, reconciliationDate)) {
            return false
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
