package com.pg.worker.settlement.application.usecase.query.dto

import java.time.LocalDateTime

data class SettlementProgressStageResult(
    val stage: SettlementProgressStage,
    val completed: Boolean,
    val completedAt: LocalDateTime?,
    val reason: String?
) {
    companion object {
        fun of(stage: SettlementProgressStage, completedAt: LocalDateTime?) = SettlementProgressStageResult(
            stage = stage,
            completed = completedAt != null,
            completedAt = completedAt,
            reason = if (completedAt != null) null else stage.incompleteReason
        )
    }
}
