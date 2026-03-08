package com.pg.worker.settlement.application.repository

import com.pg.worker.settlement.domain.SettlementLedger

interface SettlementLedgerRepository {
    fun save(ledger: SettlementLedger): SettlementLedger
    fun findByRawEventId(rawEventId: String): SettlementLedger?
}
