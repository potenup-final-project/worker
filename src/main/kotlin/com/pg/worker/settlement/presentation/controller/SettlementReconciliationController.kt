package com.pg.worker.settlement.presentation.controller

import com.pg.worker.global.exception.BusinessException
import com.pg.worker.settlement.application.service.SettlementReconciliationQueryService
import com.pg.worker.settlement.domain.ReconciliationStatus
import com.pg.worker.settlement.domain.SettlementReconciliationResultType
import com.pg.worker.settlement.domain.exception.SettlementErrorCode
import com.pg.worker.settlement.presentation.dto.SettlementReconciliationResponse
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

@RestController
class SettlementReconciliationController(
    private val reconciliationQueryService: SettlementReconciliationQueryService,
) : SettlementReconciliationApi {

    override fun getReconciliationResults(
        date: LocalDate,
        merchantId: Long?,
        resultType: SettlementReconciliationResultType?,
        status: ReconciliationStatus?,
    ): List<SettlementReconciliationResponse> {
        return reconciliationQueryService
            .getResults(date, merchantId, resultType, status)
            .map { SettlementReconciliationResponse.from(it) }
    }

    override fun getReconciliationResultById(id: Long): SettlementReconciliationResponse {
        return reconciliationQueryService.getResultById(id)
            ?.let { SettlementReconciliationResponse.from(it) }
            ?: throw BusinessException(SettlementErrorCode.RECONCILIATION_RESULT_NOT_FOUND)
    }
}
