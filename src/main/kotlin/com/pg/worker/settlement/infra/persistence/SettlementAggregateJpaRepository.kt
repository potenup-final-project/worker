package com.pg.worker.settlement.infra.persistence

import com.pg.worker.settlement.domain.SettlementAggregate
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate

interface SettlementAggregateJpaRepository : JpaRepository<SettlementAggregate, Long> {
    fun existsByMerchantIdAndSettlementBaseDate(merchantId: Long, settlementBaseDate: LocalDate): Boolean
}
