package com.pg.worker.settlement.application.service

import com.pg.worker.settlement.application.repository.ExternalSettlementDetailRepository
import com.pg.worker.settlement.application.repository.SettlementLedgerRepository
import com.pg.worker.settlement.application.repository.SettlementRawDataRepository
import com.pg.worker.settlement.domain.ExternalSettlementDetail
import com.pg.worker.settlement.domain.SettlementLedger
import com.pg.worker.settlement.domain.SettlementReconciliationResultType
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

@Service
class SettlementReconciliationEngine(
    private val externalDetailRepository: ExternalSettlementDetailRepository,
    private val ledgerRepository: SettlementLedgerRepository,
    private val rawDataRepository: SettlementRawDataRepository,
    private val writer: SettlementReconciliationWriter,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private data class ReconciliationStats(
        var matched: Int = 0,
        var amountMismatch: Int = 0,
        var feeMismatch: Int = 0,
        var partiallyMatched: Int = 0,
        var missingInternal: Int = 0,
        var missingExternal: Int = 0,
        var duplicatedInternal: Int = 0,
        var duplicatedExternal: Int = 0,
    ) {
        fun toSummaryLog(): String =
            "MATCHED=$matched, AMOUNT_MISMATCH=$amountMismatch, FEE_MISMATCH=$feeMismatch, " +
                    "PARTIALLY_MATCHED=$partiallyMatched, MISSING_INTERNAL=$missingInternal, " +
                    "MISSING_EXTERNAL=$missingExternal, DUPLICATED_INTERNAL=$duplicatedInternal, " +
                    "DUPLICATED_EXTERNAL=$duplicatedExternal"
    }

    @Transactional
    fun reconcile(baseDate: LocalDate) {
        log.info("[외부대사] 시작. 정산기준일={}", baseDate)

        // 1. 외부 데이터 로드 (기준일 기준)
        val externalMap = externalDetailRepository.findAllBySettlementBaseDate(baseDate).groupBy { it.providerTxId }
        log.info("[외부대사] 외부 정산 내역 로드 완료. 건수={}", externalMap.values.sumOf { it.size })

        // 2. 내부 원장 데이터 로드 (기준일 기준)
        val ledgers = ledgerRepository.findAllBySettlementBaseDate(baseDate)
        
        // 3. providerTxId 매칭을 위해 RawData 정보 Join (메모리상)
        val rawEventIds = ledgers.map { it.rawEventId }
        val rawDataMap = rawDataRepository.findAllByEventIdIn(rawEventIds).associateBy { it.eventId }
        
        val internalMap = ledgers.groupBy { ledger ->
            rawDataMap[ledger.rawEventId]?.providerTxId ?: "UNKNOWN_TX_${ledger.id}"
        }
        log.info("[외부대사] 내부 정산 원장 로드 완료. 건수={}", ledgers.size)

        // 4. 비교 및 분류
        val stats = compareAndClassify(baseDate, externalMap, internalMap, rawDataMap)

        log.info("[외부대사] 종료. 정산기준일={}, {}", baseDate, stats.toSummaryLog())
    }

    private fun compareAndClassify(
        baseDate: LocalDate,
        externalMap: Map<String, List<ExternalSettlementDetail>>,
        internalMap: Map<String, List<SettlementLedger>>,
        rawDataMap: Map<String, com.pg.worker.settlement.domain.SettlementRawData>
    ): ReconciliationStats {
        val allKeys = (externalMap.keys + internalMap.keys).distinct().sorted()
        log.info("[외부대사] 대사 대상 고유 providerTxId 건수={}", allKeys.size)

        val stats = ReconciliationStats()
        allKeys.forEach { providerTxId ->
            val externals = externalMap[providerTxId] ?: emptyList()
            val internals = internalMap[providerTxId] ?: emptyList()
            classifyReconciliationResult(baseDate, providerTxId, externals, internals, stats, rawDataMap)
        }
        return stats
    }

    private fun classifyReconciliationResult(
        baseDate: LocalDate,
        providerTxId: String,
        externals: List<ExternalSettlementDetail>,
        internals: List<SettlementLedger>,
        stats: ReconciliationStats,
        rawDataMap: Map<String, com.pg.worker.settlement.domain.SettlementRawData>
    ) {
        when {
            externals.size > 1 -> {
                writer.writeMismatch(
                    reconciliationDate = baseDate,
                    providerTxId = providerTxId,
                    resultType = SettlementReconciliationResultType.DUPLICATED_EXTERNAL,
                    merchantId = externals.first().merchantId,
                    externalRecordId = externals.first().id,
                    externalAmount = externals.first().amount,
                    reason = "동일 providerTxId로 외부 정산 레코드가 ${externals.size}건 존재함",
                )
                stats.duplicatedExternal++
            }

            internals.size > 1 -> {
                val firstLedger = internals.first()
                writer.writeMismatch(
                    reconciliationDate = baseDate,
                    providerTxId = providerTxId,
                    resultType = SettlementReconciliationResultType.DUPLICATED_INTERNAL,
                    merchantId = firstLedger.merchantId,
                    internalRawDataId = rawDataMap[firstLedger.rawEventId]?.id,
                    internalAmount = internals.sumOf { it.amount },
                    reason = "동일 providerTxId로 내부 정산 원장이 ${internals.size}건 존재함",
                )
                stats.duplicatedInternal++
            }

            internals.isEmpty() && externals.isNotEmpty() -> {
                val external = externals.first()
                writer.writeMismatch(
                    reconciliationDate = baseDate,
                    providerTxId = providerTxId,
                    resultType = SettlementReconciliationResultType.MISSING_INTERNAL,
                    merchantId = external.merchantId,
                    externalRecordId = external.id,
                    externalAmount = external.amount,
                    reason = "외부 정산 파일에는 존재하나 내부 원장(Ledger) 없음 (결제 누락 또는 처리 지연)",
                )
                stats.missingInternal++
            }

            externals.isEmpty() && internals.isNotEmpty() -> {
                val internal = internals.first()
                writer.writeMismatch(
                    reconciliationDate = baseDate,
                    providerTxId = providerTxId,
                    resultType = SettlementReconciliationResultType.MISSING_EXTERNAL,
                    merchantId = internal.merchantId,
                    internalRawDataId = rawDataMap[internal.rawEventId]?.id,
                    internalAmount = internal.amount,
                    reason = "내부 원장은 존재하나 외부 정산 파일에 없음 (매입 누락 가능성)",
                )
                stats.missingExternal++
            }

            else -> {
                val internal = internals.first()
                val external = externals.first()
                val rawData = rawDataMap[internal.rawEventId]
                classifyByFieldComparison(baseDate, providerTxId, internal, rawData, external, stats)
            }
        }
    }

    private fun classifyByFieldComparison(
        baseDate: LocalDate,
        providerTxId: String,
        internal: SettlementLedger,
        rawData: com.pg.worker.settlement.domain.SettlementRawData?,
        external: ExternalSettlementDetail,
        stats: ReconciliationStats,
    ) {
        val internalAbsAmount = kotlin.math.abs(internal.amount)
        val internalAbsHostFee = kotlin.math.abs(internal.hostFee)
        
        val amountMatched = internalAbsAmount == external.amount
        val feeMatched = internalAbsHostFee == external.fee
        val typeMatched = internal.ledgerType == external.transactionType

        when {
            amountMatched && feeMatched && typeMatched -> {
                writer.writeMatched(
                    reconciliationDate = baseDate,
                    rawDataId = rawData?.id,
                    merchantId = internal.merchantId,
                    providerTxId = providerTxId,
                    externalRecord = external,
                    internalAmount = internal.amount
                )
                stats.matched++
            }

            amountMatched && !feeMatched -> {
                writer.writeMismatch(
                    reconciliationDate = baseDate,
                    providerTxId = providerTxId,
                    resultType = SettlementReconciliationResultType.FEE_MISMATCH,
                    merchantId = internal.merchantId,
                    internalRawDataId = rawData?.id,
                    externalRecordId = external.id,
                    internalAmount = internal.amount,
                    externalAmount = external.amount,
                    reason = "원금은 일치하나 카드사 수수료 불일치 (내부원가=${internalAbsHostFee}, 외부=${external.fee})",
                )
                stats.feeMismatch++
            }

            amountMatched -> {
                writer.writeMismatch(
                    reconciliationDate = baseDate,
                    providerTxId = providerTxId,
                    resultType = SettlementReconciliationResultType.PARTIALLY_MATCHED,
                    merchantId = internal.merchantId,
                    internalRawDataId = rawData?.id,
                    externalRecordId = external.id,
                    internalAmount = internal.amount,
                    externalAmount = external.amount,
                    reason = "원금 일치, transactionType 불일치 (내부=${internal.ledgerType}, 외부=${external.transactionType})",
                )
                stats.partiallyMatched++
            }

            else -> {
                writer.writeMismatch(
                    reconciliationDate = baseDate,
                    providerTxId = providerTxId,
                    resultType = SettlementReconciliationResultType.AMOUNT_MISMATCH,
                    merchantId = internal.merchantId,
                    internalRawDataId = rawData?.id,
                    externalRecordId = external.id,
                    internalAmount = internal.amount,
                    externalAmount = external.amount,
                    reason = "원금 불일치 (내부절대값=${internalAbsAmount}, 외부=${external.amount})",
                )
                stats.amountMismatch++
            }
        }
    }
}
