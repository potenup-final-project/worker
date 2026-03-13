package com.pg.worker.settlement.application.repository

import com.pg.worker.settlement.domain.SettlementAggregate

interface SettlementAggregateRepository {
    fun save(aggregate: SettlementAggregate): SettlementAggregate
    fun existsByMerchantIdAndSettlementBaseDate(merchantId: Long, baseDate: java.time.LocalDate): Boolean
}
