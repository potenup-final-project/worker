package com.pg.worker.settlement.application.repository

import com.pg.worker.settlement.domain.SettlementPolicy

interface SettlementPolicyRepository {
    fun findByMerchantId(merchantId: Long): SettlementPolicy?
}
