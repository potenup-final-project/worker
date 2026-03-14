package com.pg.worker.settlement.application.repository

import com.pg.worker.settlement.domain.SettlementLedger
import java.time.LocalDate

interface SettlementLedgerRepository {
    fun save(ledger: SettlementLedger): SettlementLedger
    fun findByRawEventId(rawEventId: String): SettlementLedger?

    /**
     * 특정 원거래 ID에 대응하는 모든 정산 원장 조회
     */
    fun findAllByOriginalPaymentTxId(originalPaymentTxId: Long): List<SettlementLedger>

    /**
     * 특정 정산 기준일의 미집계 가맹점 ID 목록 조회
     */
    fun findMerchantIdsBySettlementBaseDate(baseDate: LocalDate): List<Long>

    /**
     * 특정 가맹점의 특정 정산 기준일의 미집계 Ledger 목록 조회
     */
    fun findUnaggregatedLedgers(merchantId: Long, baseDate: LocalDate): List<SettlementLedger>

    /**
     * 여러 내부 거래 ID에 대응하는 모든 정산 원장 조회 (N+1 방지용 bulk 조회)
     */
    fun findAllByTransactionIdIn(transactionIds: List<Long>): List<SettlementLedger>

    /**
     * 특정 정산 기준일의 모든 정산 원장 조회
     */
    fun findAllBySettlementBaseDate(baseDate: LocalDate): List<SettlementLedger>
}
