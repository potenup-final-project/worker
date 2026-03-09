package com.pg.worker.settlement.infra.persistence

import com.pg.worker.settlement.domain.SettlementLedger
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface SettlementLedgerJpaRepository : JpaRepository<SettlementLedger, Long> {
    fun findByRawEventId(rawEventId: String): SettlementLedger?
    fun findAllByOriginalPaymentTxId(originalPaymentTxId: Long): List<SettlementLedger>
    fun findAllByTransactionId(transactionId: Long): List<SettlementLedger>
    fun findAllByTransactionIdIn(transactionIds: List<Long>): List<SettlementLedger>
}
