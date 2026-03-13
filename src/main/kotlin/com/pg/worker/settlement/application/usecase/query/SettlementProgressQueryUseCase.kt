package com.pg.worker.settlement.application.usecase.query

import com.pg.worker.settlement.application.usecase.query.dto.SettlementProgressResult

interface SettlementProgressQueryUseCase {
    fun getByTransactionId(transactionId: Long): SettlementProgressResult
}
