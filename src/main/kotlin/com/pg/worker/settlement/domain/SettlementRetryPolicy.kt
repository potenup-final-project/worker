package com.pg.worker.settlement.domain

object SettlementRetryPolicy {
    const val PENDING_BASE_DELAY_MINUTES = 5L
    const val RETRY_BASE_DELAY_MINUTES = 1L
    const val MAX_BACKOFF_DELAY_MINUTES = 60L
    const val MAX_RETRY_COUNT = 5
    const val STUCK_THRESHOLD_MINUTES = 30L

    fun pendingNextRetryDelayMinutes(currentRetryCount: Int): Long {
        return exponentialDelayMinutes(
            currentRetryCount = currentRetryCount,
            baseDelayMinutes = PENDING_BASE_DELAY_MINUTES,
        )
    }

    fun retryableNextRetryDelayMinutes(currentRetryCount: Int): Long {
        return exponentialDelayMinutes(
            currentRetryCount = currentRetryCount,
            baseDelayMinutes = RETRY_BASE_DELAY_MINUTES,
        )
    }

    private fun exponentialDelayMinutes(currentRetryCount: Int, baseDelayMinutes: Long): Long {
        val attempt = (currentRetryCount + 1).coerceAtLeast(1)
        val multiplier = 1L shl (attempt - 1).coerceAtMost(20)
        return (baseDelayMinutes * multiplier).coerceAtMost(MAX_BACKOFF_DELAY_MINUTES)
    }
}
