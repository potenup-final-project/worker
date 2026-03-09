package com.pg.worker.settlement.application.repository

import com.pg.worker.settlement.domain.RawDataStatus
import com.pg.worker.settlement.domain.SettlementRawData
import com.pg.worker.settlement.domain.TransactionType
import java.time.LocalDateTime

interface SettlementRawDataRepository {
    fun save(data: SettlementRawData): SettlementRawData
    fun existsByEventId(eventId: String): Boolean
    fun findByPaymentKeyAndTransactionType(paymentKey: String, type: TransactionType): SettlementRawData?
    fun findById(id: Long): SettlementRawData?
    fun findByTransactionId(transactionId: Long): SettlementRawData?
    fun findRetryableDataForClaim(statuses: List<RawDataStatus>, now: LocalDateTime, limit: Int): List<SettlementRawData>
    fun findStuckProcessingDataForClaim(threshold: LocalDateTime, limit: Int): List<SettlementRawData>
}
