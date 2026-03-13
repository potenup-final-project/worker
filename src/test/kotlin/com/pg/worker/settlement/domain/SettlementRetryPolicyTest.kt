package com.pg.worker.settlement.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SettlementRetryPolicyTest {

    @Test
    fun `retryable 지연은 지수 백오프로 증가한다`() {
        assertEquals(1L, SettlementRetryPolicy.retryableNextRetryDelayMinutes(currentRetryCount = 0))
        assertEquals(2L, SettlementRetryPolicy.retryableNextRetryDelayMinutes(currentRetryCount = 1))
        assertEquals(4L, SettlementRetryPolicy.retryableNextRetryDelayMinutes(currentRetryCount = 2))
    }

    @Test
    fun `pending 지연도 지수 백오프로 증가하고 최대값을 넘지 않는다`() {
        assertEquals(5L, SettlementRetryPolicy.pendingNextRetryDelayMinutes(currentRetryCount = 0))
        assertEquals(10L, SettlementRetryPolicy.pendingNextRetryDelayMinutes(currentRetryCount = 1))
        assertEquals(60L, SettlementRetryPolicy.pendingNextRetryDelayMinutes(currentRetryCount = 4))
    }
}
