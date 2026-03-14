package com.pg.worker.settlement.application.usecase.command.dto

import java.time.LocalDateTime

sealed interface RecordSettlementResult {
    data object Success : RecordSettlementResult
    data object SkippedAlreadyProcessed : RecordSettlementResult
    data class RetryScheduled(
        val reason: String,
        val retryCount: Int,
        val nextRetryAt: LocalDateTime?
    ) : RecordSettlementResult
    data class NonRetryableFailed(val reason: String) : RecordSettlementResult
}
