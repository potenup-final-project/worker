package com.pg.worker.settlement.infra.persistence

import com.pg.worker.settlement.domain.ExternalTransactionRecord
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDateTime

interface ExternalTransactionRecordJpaRepository : JpaRepository<ExternalTransactionRecord, Long> {
    fun existsByProviderTxId(providerTxId: String): Boolean
    fun findAllByOccurredAtBetween(from: LocalDateTime, to: LocalDateTime): List<ExternalTransactionRecord>
}
