package com.pg.worker.settlement.infra.persistence

import com.pg.worker.settlement.application.repository.SettlementLedgerRepository
import com.pg.worker.settlement.domain.SettlementLedger
import org.springframework.stereotype.Repository

@Repository
class SettlementLedgerRepositoryImpl(
    private val jpaRepository: SettlementLedgerJpaRepository
) : SettlementLedgerRepository {
    override fun save(ledger: SettlementLedger): SettlementLedger {
        return jpaRepository.save(ledger)
    }

    override fun findByRawEventId(rawEventId: String): SettlementLedger? {
        return jpaRepository.findByRawEventId(rawEventId)
    }
}
