package com.pg.worker.settlement.application.service

import com.pg.worker.settlement.application.repository.ExternalTransactionRecordRepository
import com.pg.worker.settlement.application.repository.SettlementRawDataRepository
import com.pg.worker.settlement.domain.ExternalTransactionRecord
import com.pg.worker.settlement.domain.SettlementRawData
import com.pg.worker.settlement.domain.SettlementReconciliationResultType
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalTime

@Service
class SettlementReconciliationEngine(
    private val externalRecordRepository: ExternalTransactionRecordRepository,
    private val rawDataRepository: SettlementRawDataRepository,
    private val writer: SettlementReconciliationWriter,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private data class ReconciliationStats(
        var matched: Int = 0,
        var amountMismatch: Int = 0,
        var partiallyMatched: Int = 0,
        var missingInternal: Int = 0,
        var missingExternal: Int = 0,
        var duplicatedInternal: Int = 0,
        var duplicatedExternal: Int = 0,
    ) {
        fun toSummaryLog(): String =
            "MATCHED=$matched, AMOUNT_MISMATCH=$amountMismatch, PARTIALLY_MATCHED=$partiallyMatched, " +
                    "MISSING_INTERNAL=$missingInternal, MISSING_EXTERNAL=$missingExternal, " +
                    "DUPLICATED_INTERNAL=$duplicatedInternal, DUPLICATED_EXTERNAL=$duplicatedExternal"
    }

    @Transactional
    fun reconcile(baseDate: LocalDate) {
        log.info("[외부대사] 시작. 기준일={}", baseDate)

        val externalMap = loadExternalRecordsByProviderTxId(baseDate)
        val internalMap = loadInternalRawDataByProviderTxId(baseDate)
        val stats = compareAndClassify(baseDate, externalMap, internalMap)

        log.info("[외부대사] 종료. {}", stats.toSummaryLog())
    }

    private fun loadExternalRecordsByProviderTxId(baseDate: LocalDate): Map<String, List<ExternalTransactionRecord>> {
        val from = baseDate.atStartOfDay()
        val to = baseDate.atTime(LocalTime.MAX)
        val records = externalRecordRepository.findAllByOccurredAtBetween(from, to)
        log.info("[외부대사] 외부 거래 내역 로드 완료. 건수={}", records.size)
        return records.groupBy { it.providerTxId }
    }

    private fun loadInternalRawDataByProviderTxId(baseDate: LocalDate): Map<String, List<SettlementRawData>> {
        val from = baseDate.atStartOfDay()
        val to = baseDate.atTime(LocalTime.MAX)
        val rawData = rawDataRepository.findAllByEventOccurredAtBetween(from, to)
        log.info("[외부대사] 내부 RawData 로드 완료. 건수={}", rawData.size)
        return rawData.groupBy { it.providerTxId }
    }

    private fun compareAndClassify(
        baseDate: LocalDate,
        externalMap: Map<String, List<ExternalTransactionRecord>>,
        internalMap: Map<String, List<SettlementRawData>>,
    ): ReconciliationStats {
        val allKeys = (externalMap.keys + internalMap.keys).sorted()
        log.info("[외부대사] 대사 대상 고유 providerTxId 건수={}", allKeys.size)

        val stats = ReconciliationStats()
        allKeys.forEach { providerTxId ->
            val externals = externalMap[providerTxId] ?: emptyList()
            val internals = internalMap[providerTxId] ?: emptyList()
            classifyReconciliationResult(baseDate, providerTxId, externals, internals, stats)
        }
        return stats
    }

    private fun classifyReconciliationResult(
        baseDate: LocalDate,
        providerTxId: String,
        externals: List<ExternalTransactionRecord>,
        internals: List<SettlementRawData>,
        stats: ReconciliationStats,
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
                writer.writeMismatch(
                    reconciliationDate = baseDate,
                    providerTxId = providerTxId,
                    resultType = SettlementReconciliationResultType.DUPLICATED_INTERNAL,
                    merchantId = internals.first().merchantId,
                    internalRawDataId = internals.first().id,
                    internalAmount = internals.first().amount,
                    reason = "동일 providerTxId로 내부 RawData가 ${internals.size}건 존재함",
                )
                stats.duplicatedInternal++
            }

            internals.isEmpty() && externals.isNotEmpty() -> {
                writer.writeMismatch(
                    reconciliationDate = baseDate,
                    providerTxId = providerTxId,
                    resultType = SettlementReconciliationResultType.MISSING_INTERNAL,
                    merchantId = externals.first().merchantId,
                    externalRecordId = externals.first().id,
                    externalAmount = externals.first().amount,
                    reason = "외부 정산 파일에 존재하나 내부 RawData 없음 (이벤트 미수신 가능성)",
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
                    internalRawDataId = internal.id,
                    internalAmount = internal.amount,
                    reason = "내부 RawData는 존재하나 외부 정산 파일에 없음",
                )
                stats.missingExternal++
            }

            else -> {
                val internal = internals.first()
                val external = externals.first()
                classifyByFieldComparison(baseDate, providerTxId, internal, external, stats)
            }
        }
    }

    private fun classifyByFieldComparison(
        baseDate: LocalDate,
        providerTxId: String,
        internal: SettlementRawData,
        external: ExternalTransactionRecord,
        stats: ReconciliationStats,
    ) {
        val amountMatched = internal.amount == external.amount
        val typeMatched = internal.transactionType == external.transactionType

        when {
            amountMatched && typeMatched -> {
                writer.writeMatched(
                    reconciliationDate = baseDate,
                    rawData = internal,
                    externalRecord = external,
                )
                stats.matched++
            }

            amountMatched && !typeMatched -> {
                writer.writeMismatch(
                    reconciliationDate = baseDate,
                    providerTxId = providerTxId,
                    resultType = SettlementReconciliationResultType.PARTIALLY_MATCHED,
                    merchantId = internal.merchantId,
                    internalRawDataId = internal.id,
                    externalRecordId = external.id,
                    internalAmount = internal.amount,
                    externalAmount = external.amount,
                    reason = "원금 일치, transactionType 불일치 " +
                            "(내부=${internal.transactionType}, 외부=${external.transactionType})",
                )
                stats.partiallyMatched++
            }

            else -> {
                writer.writeMismatch(
                    reconciliationDate = baseDate,
                    providerTxId = providerTxId,
                    resultType = SettlementReconciliationResultType.AMOUNT_MISMATCH,
                    merchantId = internal.merchantId,
                    internalRawDataId = internal.id,
                    externalRecordId = external.id,
                    internalAmount = internal.amount,
                    externalAmount = external.amount,
                    reason = "원금 불일치 (내부=${internal.amount}, 외부=${external.amount}, 차이=${external.amount - internal.amount})",
                )
                stats.amountMismatch++
            }
        }
    }
}
