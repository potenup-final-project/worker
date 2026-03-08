package com.pg.worker.settlement.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.LocalDateTime

@Entity
@Table(name = "settlement_policies")
class SettlementPolicy protected constructor(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "merchant_id", nullable = false, unique = true)
    val merchantId: Long,

    @Column(name = "fee_rate", nullable = false, precision = 5, scale = 4)
    var feeRate: BigDecimal,

    // 정산 주기(일 단위)
    @Column(name = "settlement_cycle_days", nullable = false)
    var settlementCycleDays: Int,

    @Column(name = "bank_code", length = 10, nullable = false)
    var bankCode: String,

    @Column(name = "account_number", length = 50, nullable = false)
    var accountNumber: String,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
) {
    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now()
        protected set
}
