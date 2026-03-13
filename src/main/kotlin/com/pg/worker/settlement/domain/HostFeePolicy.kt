package com.pg.worker.settlement.domain

import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

@Component
class HostFeePolicy {
    // 카드사 원가 수수료율 고정 (테스트용 2.1%)
    private val HOST_FEE_RATE = BigDecimal("0.021")

    /**
     * 카드사 원가 수수료 계산 (카드사가 가져가는 몫)
     */
    fun calculateHostFee(amount: Long): Long {
        return calculate(amount, HOST_FEE_RATE)
    }

    /**
     * 수수료율에 따른 금액 계산 (공통)
     */
    fun calculateFee(amount: Long, feeRate: BigDecimal): Long {
        return calculate(amount, feeRate)
    }

    private fun calculate(amount: Long, rate: BigDecimal): Long {
        return (amount.toBigDecimal() * rate)
            .setScale(0, RoundingMode.HALF_UP)
            .toLong()
    }
}
