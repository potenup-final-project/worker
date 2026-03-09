package com.pg.worker.settlement.infra.persistence

import com.pg.worker.settlement.application.repository.SettlementPolicyRepository
import com.pg.worker.settlement.domain.SettlementPolicy
import org.springframework.stereotype.Repository

@Repository
class SettlementPolicyRepositoryImpl(
    private val jpaRepository: SettlementPolicyJpaRepository
) : SettlementPolicyRepository {
    override fun findByMerchantId(merchantId: Long): SettlementPolicy? {
        return jpaRepository.findByMerchantId(merchantId)
    }
}
