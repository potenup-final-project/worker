package com.pg.worker.settlement.presentation.dto

import com.pg.worker.settlement.application.usecase.query.dto.SettlementProgressResult
import com.pg.worker.settlement.application.usecase.query.dto.SettlementProgressStageResult
import java.time.LocalDateTime

data class SettlementProgressResponse(
    val paymentId: Long,
    val transactionId: Long,
    val paymentType: String,
    val currentStage: String?,
    val isBlocked: Boolean,
    val blockedReason: String?,
    val blockedType: String?,
    val stages: List<SettlementProgressStageResponse>
) {
    companion object {
        fun from(result: SettlementProgressResult) = SettlementProgressResponse(
            paymentId = result.paymentId,
            transactionId = result.transactionId,
            paymentType = result.paymentType,
            currentStage = result.currentStage?.name,
            isBlocked = result.isBlocked,
            blockedReason = result.blockedReason,
            blockedType = result.blockedType,
            stages = result.stages.map { SettlementProgressStageResponse.from(it) }
        )
    }
}

data class SettlementProgressStageResponse(
    val stage: String,
    val completed: Boolean,
    val completedAt: LocalDateTime?,
    val reason: String?
) {
    companion object {
        fun from(result: SettlementProgressStageResult) =
            SettlementProgressStageResponse(
                stage = result.stage.name,
                completed = result.completed,
                completedAt = result.completedAt,
                reason = result.reason
            )
    }
}
