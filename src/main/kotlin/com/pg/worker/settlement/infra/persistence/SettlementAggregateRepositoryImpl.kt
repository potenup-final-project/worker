package com.pg.worker.settlement.infra.persistence

import com.pg.worker.settlement.application.repository.SettlementAggregateRepository
import com.pg.worker.settlement.domain.SettlementAggregate
import org.springframework.stereotype.Repository
import java.time.LocalDate

@Repository
class SettlementAggregateRepositoryImpl(
    private val jpaRepository: SettlementAggregateJpaRepository
) : SettlementAggregateRepository {
    override fun save(aggregate: SettlementAggregate): SettlementAggregate {
        return jpaRepository.save(aggregate)
    }

    override fun findById(id: Long): SettlementAggregate? {
        return jpaRepository.findById(id).orElse(null)
    }

    override fun existsByMerchantIdAndSettlementBaseDate(merchantId: Long, baseDate: LocalDate): Boolean {
        return jpaRepository.existsByMerchantIdAndSettlementBaseDate(merchantId, baseDate)
    }
}
