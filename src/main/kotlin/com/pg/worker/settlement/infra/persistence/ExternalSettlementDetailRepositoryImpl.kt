package com.pg.worker.settlement.infra.persistence

import com.pg.worker.settlement.application.repository.ExternalSettlementDetailRepository
import com.pg.worker.settlement.domain.ExternalSettlementDetail
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Repository
class ExternalSettlementDetailRepositoryImpl(
    private val jpaRepository: ExternalSettlementDetailJpaRepository,
) : ExternalSettlementDetailRepository {
    override fun saveAll(records: List<ExternalSettlementDetail>): List<ExternalSettlementDetail> =
        jpaRepository.saveAll(records)

    override fun existsByProviderTxId(providerTxId: String): Boolean =
        jpaRepository.existsByProviderTxId(providerTxId)

    override fun findAllByOccurredAtBetween(from: LocalDateTime, to: LocalDateTime): List<ExternalSettlementDetail> =
        jpaRepository.findAllByOccurredAtBetween(from, to)

    override fun findAllBySettlementBaseDate(baseDate: java.time.LocalDate): List<ExternalSettlementDetail> {
        return jpaRepository.findAllBySettlementBaseDate(baseDate)
    }
}
