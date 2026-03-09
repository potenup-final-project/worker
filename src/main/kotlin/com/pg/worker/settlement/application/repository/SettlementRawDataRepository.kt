package com.pg.worker.settlement.application.repository

import com.pg.worker.settlement.domain.SettlementRawData
import com.pg.worker.settlement.domain.TransactionType

interface SettlementRawDataRepository {
    fun save(data: SettlementRawData): SettlementRawData
    fun existsByEventId(eventId: String): Boolean
    fun findByPaymentKeyAndTransactionType(paymentKey: String, type: TransactionType): SettlementRawData?
    fun findById(id: Long): SettlementRawData?
}
