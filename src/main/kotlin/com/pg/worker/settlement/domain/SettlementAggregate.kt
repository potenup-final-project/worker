package com.pg.worker.settlement.domain

import jakarta.persistence.*
import java.time.LocalDate
import java.time.LocalDateTime

@Entity
@Table(
    name = "settlement_aggregates",
    indexes = [
        Index(name = "idx_aggregate_status", columnList = "status"),
        Index(name = "idx_aggregate_merchant_date", columnList = "merchant_id, settlement_base_date", unique = true)
    ]
)
class SettlementAggregate protected constructor(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "merchant_id", nullable = false)
    val merchantId: Long,

    @Column(name = "settlement_base_date", nullable = false)
    val settlementBaseDate: LocalDate,

    /**
     * totalApproveAmount: 승인 금액 총합 (양수)
     */
    @Column(name = "total_approve_amount", nullable = false)
    val totalApproveAmount: Long,

    /**
     * totalCancelAmount: 취소 금액 총합 (양수 저장)
     */
    @Column(name = "total_cancel_amount", nullable = false)
    val totalCancelAmount: Long,

    /**
     * totalFeeAmount: 순수수료 총합 (승인 수수료 + 취소 환급 수수료(음수))
     */
    @Column(name = "total_fee_amount", nullable = false)
    val totalFeeAmount: Long,

    /**
     * netSettlementAmount: 최종 지급 대상 금액 (승인 - 취소 - 순수수료)
     */
    @Column(name = "net_settlement_amount", nullable = false)
    val netSettlementAmount: Long,

    @Column(name = "ledger_count", nullable = false)
    val ledgerCount: Int,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: SettlementAggregateStatus = SettlementAggregateStatus.READY,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now()
) {
    companion object {
        fun create(
            merchantId: Long,
            settlementBaseDate: LocalDate,
            totalApproveAmount: Long,
            totalCancelAmount: Long,
            totalFeeAmount: Long,
            ledgerCount: Int
        ): SettlementAggregate {
            val netAmount = totalApproveAmount - totalCancelAmount - totalFeeAmount
            return SettlementAggregate(
                merchantId = merchantId,
                settlementBaseDate = settlementBaseDate,
                totalApproveAmount = totalApproveAmount,
                totalCancelAmount = totalCancelAmount,
                totalFeeAmount = totalFeeAmount,
                netSettlementAmount = netAmount,
                ledgerCount = ledgerCount
            )
        }
    }
}
