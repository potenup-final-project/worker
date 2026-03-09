package com.pg.worker.settlement.application.repository

import com.pg.worker.settlement.domain.SettlementLedger
import java.time.LocalDate

interface SettlementLedgerRepository {
    fun save(ledger: SettlementLedger): SettlementLedger
    fun findByRawEventId(rawEventId: String): SettlementLedger?

    /**
     * 특정 정산 기준일의 미집계 가맹점 ID 목록 조회
     */
    fun findMerchantIdsBySettlementBaseDate(baseDate: LocalDate): List<Long>

    /**
     * 특정 가맹점의 특정 정산 기준일의 미집계 Ledger 목록 조회
     */
    fun findUnaggregatedLedgers(merchantId: Long, baseDate: LocalDate): List<SettlementLedger>
}
