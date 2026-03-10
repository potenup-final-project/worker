package com.pg.worker.settlement.application.service

import com.pg.worker.settlement.application.repository.SettlementReconciliationResultRepository
import com.pg.worker.settlement.domain.ReconciliationStatus
import com.pg.worker.settlement.domain.SettlementReconciliationResult
import com.pg.worker.settlement.domain.SettlementReconciliationResultType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

@Service
@Transactional(readOnly = true)
class SettlementReconciliationQueryService(
    private val reconciliationResultRepository: SettlementReconciliationResultRepository,
) {
    fun getResults(
        reconciliationDate: LocalDate,
        merchantId: Long?,
        resultType: SettlementReconciliationResultType?,
        status: ReconciliationStatus?,
    ): List<SettlementReconciliationResult> {
        val results = when {
            merchantId != null -> reconciliationResultRepository
                .findAllByReconciliationDateAndMerchantId(reconciliationDate, merchantId)
            resultType != null -> reconciliationResultRepository
                .findAllByReconciliationDateAndResultType(reconciliationDate, resultType)
            status != null -> reconciliationResultRepository
                .findAllByReconciliationDateAndStatus(reconciliationDate, status)
            else -> reconciliationResultRepository
                .findAllByReconciliationDate(reconciliationDate)
        }

        return results
            .filter { resultType == null || it.resultType == resultType }
            .filter { status == null || it.status == status }
    }

    fun getResultById(id: Long): SettlementReconciliationResult? {
        return reconciliationResultRepository.findById(id)
    }
}
