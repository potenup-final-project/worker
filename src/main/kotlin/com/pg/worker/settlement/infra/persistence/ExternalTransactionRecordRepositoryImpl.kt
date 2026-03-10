package com.pg.worker.settlement.infra.persistence

import com.pg.worker.settlement.application.repository.ExternalTransactionRecordRepository
import com.pg.worker.settlement.domain.ExternalTransactionRecord
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Repository
class ExternalTransactionRecordRepositoryImpl(
    private val jpaRepository: ExternalTransactionRecordJpaRepository,
) : ExternalTransactionRecordRepository {

    override fun save(record: ExternalTransactionRecord): ExternalTransactionRecord =
        jpaRepository.save(record)

    override fun saveAll(records: List<ExternalTransactionRecord>): List<ExternalTransactionRecord> =
        jpaRepository.saveAll(records)

    override fun existsByProviderTxId(providerTxId: String): Boolean =
        jpaRepository.existsByProviderTxId(providerTxId)

    override fun findAllByOccurredAtBetween(from: LocalDateTime, to: LocalDateTime): List<ExternalTransactionRecord> =
        jpaRepository.findAllByOccurredAtBetween(from, to)

    override fun findById(id: Long): ExternalTransactionRecord? =
        jpaRepository.findById(id).orElse(null)
}
