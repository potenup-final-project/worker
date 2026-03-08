package com.pg.worker.settlement.infra.persistence

import com.pg.worker.settlement.domain.SettlementAggregateItem
import org.springframework.data.jpa.repository.JpaRepository

interface SettlementAggregateItemJpaRepository : JpaRepository<SettlementAggregateItem, Long>
