package com.pg.worker.settlement.infra.persistence

import com.pg.worker.settlement.domain.SettlementRawData
import com.pg.worker.settlement.domain.TransactionType
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDateTime

interface SettlementRawDataJpaRepository : JpaRepository<SettlementRawData, Long> {
    fun existsByEventId(eventId: String): Boolean
    fun findByPaymentKeyAndTransactionType(paymentKey: String, type: TransactionType): SettlementRawData?
    fun findAllByTransactionIdIn(transactionIds: List<Long>): List<SettlementRawData>
    fun findAllByEventIdIn(eventIds: List<String>): List<SettlementRawData>
}
