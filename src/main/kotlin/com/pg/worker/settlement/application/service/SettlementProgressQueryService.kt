package com.pg.worker.settlement.application.service

import com.pg.worker.global.exception.BusinessException
import com.pg.worker.settlement.application.repository.InternalReconciliationResultRepository
import com.pg.worker.settlement.application.repository.PaymentTransactionRepository
import com.pg.worker.settlement.application.repository.SettlementAggregateItemRepository
import com.pg.worker.settlement.application.repository.SettlementAggregateRepository
import com.pg.worker.settlement.application.repository.SettlementLedgerRepository
import com.pg.worker.settlement.application.repository.SettlementRawDataRepository
import com.pg.worker.settlement.application.usecase.query.SettlementProgressQueryUseCase
import com.pg.worker.settlement.application.usecase.query.dto.SettlementProgressResult
import com.pg.worker.settlement.application.usecase.query.dto.SettlementProgressStage
import com.pg.worker.settlement.application.usecase.query.dto.SettlementProgressStageResult
import com.pg.worker.settlement.domain.exception.SettlementErrorCode
import com.pg.worker.settlement.domain.PaymentTxStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class SettlementProgressQueryService(
    private val paymentTransactionRepository: PaymentTransactionRepository,
    private val settlementRawDataRepository: SettlementRawDataRepository,
    private val settlementLedgerRepository: SettlementLedgerRepository,
    private val settlementAggregateItemRepository: SettlementAggregateItemRepository,
    private val settlementAggregateRepository: SettlementAggregateRepository,
    private val internalReconciliationResultRepository: InternalReconciliationResultRepository
) : SettlementProgressQueryUseCase {

    override fun getByTransactionId(transactionId: Long): SettlementProgressResult {
        val context = loadSettlementProgressContext(transactionId)
        val stageProgresses = resolveAllStageProgresses(context)
        return assembleProgressResult(context, stageProgresses)
    }

    private fun loadSettlementProgressContext(transactionId: Long): SettlementProgressContext {
        val paymentTransaction = paymentTransactionRepository.findById(transactionId)
            ?: throw BusinessException(SettlementErrorCode.PAYMENT_TRANSACTION_NOT_FOUND)
        val rawData = settlementRawDataRepository.findByTransactionId(transactionId)
        val settlementLedger = settlementLedgerRepository.findLatestByTransactionId(transactionId)
        val aggregateItem = settlementLedger?.let { settlementAggregateItemRepository.findByLedgerId(it.id) }
        val aggregate = aggregateItem?.let { settlementAggregateRepository.findById(it.aggregateId) }
        val openMismatch = internalReconciliationResultRepository.findFirstOpenMismatchByTransactionId(transactionId)

        return SettlementProgressContext(
            paymentTransaction = paymentTransaction,
            rawData = rawData,
            settlementLedger = settlementLedger,
            aggregateItem = aggregateItem,
            aggregate = aggregate,
            openMismatch = openMismatch
        )
    }

    private fun resolveAllStageProgresses(context: SettlementProgressContext): List<SettlementProgressStageResult> {
        val tx = context.paymentTransaction
        val paymentConfirmedAt = if (tx.status == PaymentTxStatus.SUCCESS) tx.confirmedAt ?: tx.createdAt else null

        return listOf(
            SettlementProgressStageResult.of(SettlementProgressStage.PAYMENT_CONFIRMED, paymentConfirmedAt),
            SettlementProgressStageResult.of(SettlementProgressStage.RAW_RECORDED,      context.rawData?.createdAt),
            SettlementProgressStageResult.of(SettlementProgressStage.LEDGER_CREATED,    context.settlementLedger?.createdAt),
            SettlementProgressStageResult.of(SettlementProgressStage.AGGREGATED,        context.aggregateItem?.createdAt),
            SettlementProgressStageResult.of(SettlementProgressStage.READY_FOR_PAYOUT,  context.aggregate?.createdAt),
            SettlementProgressStageResult.of(SettlementProgressStage.PAID_OUT,          null),
        )
    }

    private fun assembleProgressResult(
        context: SettlementProgressContext,
        stageProgresses: List<SettlementProgressStageResult>
    ): SettlementProgressResult {
        val tx = context.paymentTransaction
        val currentStage = stageProgresses.lastOrNull { it.completed }?.stage
        val openMismatch = context.openMismatch

        return SettlementProgressResult(
            paymentId = tx.paymentId,
            transactionId = tx.id,
            paymentType = tx.type.name,
            currentStage = currentStage,
            isBlocked = openMismatch != null,
            blockedReason = openMismatch?.reason,
            blockedType = openMismatch?.mismatchType?.name,
            stages = stageProgresses
        )
    }
}

