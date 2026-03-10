package com.pg.worker.settlement.infra.persistence

import com.pg.worker.settlement.application.repository.SettlementAggregateItemRepository
import com.pg.worker.settlement.domain.SettlementAggregateItem
import org.springframework.stereotype.Repository

@Repository
class SettlementAggregateItemRepositoryImpl(
    private val jpaRepository: SettlementAggregateItemJpaRepository
) : SettlementAggregateItemRepository {
    override fun saveAll(items: List<SettlementAggregateItem>): List<SettlementAggregateItem> {
        return jpaRepository.saveAll(items)
    }

    override fun findByLedgerId(ledgerId: Long): SettlementAggregateItem? {
        return jpaRepository.findByLedgerId(ledgerId)
    }
}
