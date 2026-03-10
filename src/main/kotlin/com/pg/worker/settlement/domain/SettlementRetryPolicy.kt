package com.pg.worker.settlement.domain

object SettlementRetryPolicy {
    const val PENDING_DELAY_MINUTES = 10L
    const val RETRY_DELAY_MINUTES = 5L
    const val MAX_RETRY_COUNT = 5
    const val STUCK_THRESHOLD_MINUTES = 30L
}
