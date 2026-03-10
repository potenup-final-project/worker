package com.pg.worker.settlement.application.usecase.query.dto

data class SettlementProgressResult(
    val paymentId: Long,
    val transactionId: Long,
    val paymentType: String,
    val currentStage: SettlementProgressStage?,
    val isBlocked: Boolean,
    val blockedReason: String?,
    val blockedType: String?,
    val stages: List<SettlementProgressStageResult>
)
