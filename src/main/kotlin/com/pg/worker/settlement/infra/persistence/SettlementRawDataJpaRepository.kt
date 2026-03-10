package com.pg.worker.settlement.infra.persistence

import com.pg.worker.settlement.domain.SettlementRawData
import com.pg.worker.settlement.domain.TransactionType
import org.springframework.data.jpa.repository.JpaRepository

interface SettlementRawDataJpaRepository : JpaRepository<SettlementRawData, Long> {
    fun existsByEventId(eventId: String): Boolean
    fun findByPaymentKeyAndTransactionType(paymentKey: String, type: TransactionType): SettlementRawData?
    fun findByTransactionId(transactionId: Long): SettlementRawData?
    fun findAllByTransactionIdIn(transactionIds: List<Long>): List<SettlementRawData>
}
