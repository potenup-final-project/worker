package com.pg.worker.settlement.application.service

import com.pg.worker.settlement.domain.InternalReconciliationResult
import com.pg.worker.settlement.domain.PaymentTransaction
import com.pg.worker.settlement.domain.SettlementAggregate
import com.pg.worker.settlement.domain.SettlementAggregateItem
import com.pg.worker.settlement.domain.SettlementLedger
import com.pg.worker.settlement.domain.SettlementRawData

data class SettlementProgressContext(
    val paymentTransaction: PaymentTransaction,
    val rawData: SettlementRawData?,
    val settlementLedger: SettlementLedger?,
    val aggregateItem: SettlementAggregateItem?,
    val aggregate: SettlementAggregate?,
    val openMismatch: InternalReconciliationResult?
)
