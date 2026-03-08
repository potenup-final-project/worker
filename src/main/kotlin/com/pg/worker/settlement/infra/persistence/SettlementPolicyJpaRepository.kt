package com.pg.worker.settlement.infra.persistence

import com.pg.worker.settlement.domain.SettlementPolicy
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface SettlementPolicyJpaRepository : JpaRepository<SettlementPolicy, Long> {
    fun findByMerchantId(merchantId: Long): SettlementPolicy?
}
