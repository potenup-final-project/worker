package com.pg.worker.settlement.infra.persistence

import com.pg.worker.settlement.domain.ExternalSettlementDetail
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDateTime

interface ExternalSettlementDetailJpaRepository : JpaRepository<ExternalSettlementDetail, Long> {
    fun existsByProviderTxId(providerTxId: String): Boolean
    fun findAllByOccurredAtBetween(from: LocalDateTime, to: LocalDateTime): List<ExternalSettlementDetail>
    fun findAllBySettlementBaseDate(baseDate: java.time.LocalDate): List<ExternalSettlementDetail>
}
