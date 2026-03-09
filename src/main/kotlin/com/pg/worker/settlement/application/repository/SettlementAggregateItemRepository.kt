package com.pg.worker.settlement.application.repository

import com.pg.worker.settlement.domain.SettlementAggregateItem

interface SettlementAggregateItemRepository {
    fun save(item: SettlementAggregateItem): SettlementAggregateItem
    fun saveAll(items: List<SettlementAggregateItem>): List<SettlementAggregateItem>
}
