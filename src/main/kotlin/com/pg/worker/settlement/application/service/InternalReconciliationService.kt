package com.pg.worker.settlement.application.service

import com.pg.worker.settlement.application.repository.InternalReconciliationResultRepository
import com.pg.worker.settlement.application.repository.PaymentTransactionRepository
import com.pg.worker.settlement.application.repository.SettlementLedgerRepository
import com.pg.worker.settlement.domain.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

@Service
class InternalReconciliationService(
    private val paymentTransactionRepository: PaymentTransactionRepository,
    private val ledgerRepository: SettlementLedgerRepository,
    private val reconciliationResultRepository: InternalReconciliationResultRepository,
    private val reconciliationWriter: InternalReconciliationWriter
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private data class ReconciliationStats(
        var missing: Int = 0,
        var duplicated: Int = 0,
        var typeMismatch: Int = 0,
        var amountMismatch: Int = 0,
    )

    @Transactional
    fun detectMismatches(targetDate: LocalDate) {
        log.info("[내부대사-신규] 시작. 대상일={}", targetDate)

        val transactions = paymentTransactionRepository.findSuccessfulTransactions(targetDate)
        log.info("[내부대사-신규] 대사 대상 거래 건수: {}", transactions.size)

        val stats = ReconciliationStats()

        transactions.forEach { tx ->
            val ledgers = ledgerRepository.findAllByTransactionId(tx.id)
            val expectedType = tx.type.toLedgerType()
            val sameTypeLedgers = ledgers.filter { it.ledgerType == expectedType }

            when {
                sameTypeLedgers.size > 1 -> {
                    if (detectDuplicate(tx, sameTypeLedgers, targetDate)) stats.duplicated++
                }

                ledgers.isNotEmpty() && sameTypeLedgers.isEmpty() -> {
                    if (detectTypeMismatch(tx, ledgers, expectedType, targetDate)) stats.typeMismatch++
                }

                ledgers.isEmpty() -> {
                    if (detectMissingLedger(tx, targetDate)) stats.missing++
                }

                else -> {
                    if (!isAmountMatched(tx, sameTypeLedgers.first())) {
                        if (detectAmountMismatch(tx, sameTypeLedgers.first(), targetDate)) stats.amountMismatch++
                    }
                }
            }
        }

        log.info(
            "[내부대사-신규] 종료. 결과 요약: MISSING={}, DUPLICATED={}, TYPE_MISMATCH={}, AMOUNT_MISMATCH={}",
            stats.missing,
            stats.duplicated,
            stats.typeMismatch,
            stats.amountMismatch
        )
    }

    private fun detectDuplicate(
        tx: PaymentTransaction, sameTypeLedgers: List<SettlementLedger>, targetDate: LocalDate
    ): Boolean = reconciliationWriter.writeMismatch(
        tx, targetDate, MismatchType.DUPLICATED_LEDGER, "동일 거래에 대해 중복된 정산 원장이 존재함 (건수: ${sameTypeLedgers.size})"
    )

    private fun detectTypeMismatch(
        tx: PaymentTransaction, ledgers: List<SettlementLedger>, expectedType: TransactionType, targetDate: LocalDate
    ): Boolean {
        val actualTypes = ledgers.map { it.ledgerType }.distinct()
        return reconciliationWriter.writeMismatch(
            tx, targetDate, MismatchType.TYPE_MISMATCH, "정산 원장은 존재하나 타입이 일치하지 않음 (기대: $expectedType, 실제: $actualTypes)"
        )
    }

    private fun detectMissingLedger(tx: PaymentTransaction, targetDate: LocalDate): Boolean =
        reconciliationWriter.writeMismatch(
            tx, targetDate, MismatchType.MISSING_LEDGER, "결제 성공 거래에 대응하는 정산 원장이 존재하지 않음"
        )

    private fun isAmountMatched(tx: PaymentTransaction, ledger: SettlementLedger): Boolean {
        val comparableLedgerAmount = kotlin.math.abs(ledger.amount)
        return tx.requestedAmount == comparableLedgerAmount
    }

    private fun detectAmountMismatch(
        tx: PaymentTransaction, ledger: SettlementLedger, targetDate: LocalDate
    ): Boolean {
        val comparableLedgerAmount = kotlin.math.abs(ledger.amount)
        val reason = if (tx.type == PaymentTxType.CANCEL) {
            "정산 원장의 금액이 일치하지 않음 (거래금액=${tx.requestedAmount}, 원장금액(절댓값)=$comparableLedgerAmount)"
        } else {
            "정산 원장의 금액이 일치하지 않음 (거래금액=${tx.requestedAmount}, 원장금액=$comparableLedgerAmount)"
        }
        return reconciliationWriter.writeMismatch(tx, targetDate, MismatchType.AMOUNT_MISMATCH, reason)
    }

    @Transactional
    fun resolveOpenMismatches() {
        val autoResolvableTypes = listOf(
            MismatchType.MISSING_LEDGER,
            MismatchType.DUPLICATED_LEDGER,
            MismatchType.TYPE_MISMATCH,
        )
        val lookBackDate = LocalDate.now().minusDays(30)
        log.info("[내부대사-재검사] 시작. 기준일(이후): {}, 대상 타입: {}", lookBackDate, autoResolvableTypes)

        val openResults = reconciliationResultRepository.findAllByStatusAndMismatchTypeInAndReconciliationDateAfter(
            ReconciliationStatus.OPEN, autoResolvableTypes, lookBackDate
        )
        log.info("[내부대사-재검사] 대상 OPEN 건수: {}", openResults.size)

        if (openResults.isEmpty()) {
            log.info("[내부대사-재검사] 종료. 자동 해결 건수: 0")
            return
        }

        val openTransactionIds = openResults.map { it.transactionId }.distinct()
        val transactionById = paymentTransactionRepository.findAllByIdIn(openTransactionIds).associateBy { it.id }
        val ledgersByTransactionId = ledgerRepository.findAllByTransactionIdIn(openTransactionIds).groupBy { it.transactionId }

        var resolvedCount = 0
        openResults.forEach { result ->
            val tx = transactionById[result.transactionId] ?: return@forEach log.warn(
                "[내부대사-재검사] 거래 정보 누락. tx_id: {}",
                result.transactionId
            )

            val ledgers = ledgersByTransactionId[result.transactionId] ?: emptyList()
            val expectedType = tx.type.toLedgerType()

            if (ledgers.size == 1 && ledgers.first().ledgerType == expectedType) {
                if (reconciliationWriter.resolveIfOpen(
                        result.transactionId, result.mismatchType, ledgers.first().id, "재검사 과정에서 정상이 확인되어 해결됨"
                    )
                ) {
                    resolvedCount++
                }
            }
        }

        log.info("[내부대사-재검사] 종료. 자동 해결 건수: {}", resolvedCount)
    }
}
