package com.pg.worker.settlement.presentation.controller

import com.pg.worker.settlement.application.usecase.query.SettlementProgressQueryUseCase
import com.pg.worker.settlement.presentation.dto.SettlementProgressResponse
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

@RestController
class SettlementProgressController(
    private val settlementProgressQueryUseCase: SettlementProgressQueryUseCase
) : SettlementProgressApi {

    override fun getTransactionProgress(@PathVariable transactionId: Long): SettlementProgressResponse {
        return SettlementProgressResponse.from(
            settlementProgressQueryUseCase.getByTransactionId(transactionId)
        )
    }
}
