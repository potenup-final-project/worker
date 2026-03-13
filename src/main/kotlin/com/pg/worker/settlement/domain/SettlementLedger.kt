package com.pg.worker.settlement.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

@Entity
@Table(
    name = "settlement_ledgers",
    indexes = [
        Index(name = "idx_ledger_raw_event_id", columnList = "raw_event_id", unique = true),
        Index(name = "idx_ledger_merchant_date", columnList = "merchant_id, settlement_base_date"),
        Index(name = "idx_ledger_origin_tx", columnList = "original_payment_tx_id")
    ]
)
class SettlementLedger protected constructor(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "raw_event_id", nullable = false, unique = true, updatable = false)
    val rawEventId: String,

    @Column(name = "merchant_id", nullable = false, updatable = false)
    val merchantId: Long,

    @Column(name = "payment_key", length = 80, nullable = false, updatable = false)
    val paymentKey: String,

    /**
     * 현재 ledger가 나타내는 "이번 거래 자체"의 내부 거래 ID.
     */
    @Column(name = "transaction_id", nullable = false, updatable = false)
    val transactionId: Long,

    /**
     * 취소 거래가 참조하는 원승인 거래 ID.
     */
    @Column(name = "original_payment_tx_id")
    val originalPaymentTxId: Long? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "ledger_type", nullable = false, updatable = false)
    val ledgerType: TransactionType,

    /**
     * 정산 대상 원금 금액.
     */
    @Column(name = "amount", nullable = false, updatable = false)
    val amount: Long,

    /**
     * 외부 원가 수수료 (카드사 등)
     */
    @Column(name = "host_fee", nullable = false, updatable = false)
    val hostFee: Long,

    /**
     * 우리 플랫폼 서비스 수수료 (마진)
     */
    @Column(name = "service_fee", nullable = false, updatable = false)
    val serviceFee: Long,

    /**
     * 총 수수료 합계 (host_fee + service_fee)
     */
    @Column(name = "total_fee", nullable = false, updatable = false)
    val totalFee: Long,

    /**
     * 가맹점에게 실지급될 금액 (amount - totalFee)
     * amount가 음수(취소)인 경우, 지급액도 음수가 됨.
     */
    @Column(name = "settlement_amount", nullable = false, updatable = false)
    val settlementAmount: Long,

    @Column(name = "occurred_at", nullable = false, updatable = false)
    val occurredAt: LocalDateTime,

    @Column(name = "settlement_base_date", nullable = false, updatable = false)
    val settlementBaseDate: LocalDate,

    @Column(name = "settlement_policy_id", nullable = false)
    val settlementPolicyId: Long,

    @Column(name = "policy_fee_rate", nullable = false, precision = 5, scale = 4)
    val policyFeeRate: BigDecimal,

    @Column(name = "policy_settlement_cycle_days", nullable = false)
    val policySettlementCycleDays: Int,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
) {
    companion object {
        fun create(
            raw: SettlementRawData,
            originalPaymentTxId: Long?,
            hostFee: Long,
            serviceFee: Long,
            settlementBaseDate: LocalDate,
            policy: SettlementPolicy
        ): SettlementLedger = create(
            raw = raw,
            originalPaymentTxId = originalPaymentTxId,
            hostFee = hostFee,
            serviceFee = serviceFee,
            settlementBaseDate = settlementBaseDate,
            settlementPolicyId = policy.id,
            policyFeeRate = policy.feeRate,
            policySettlementCycleDays = policy.settlementCycleDays
        )

        fun create(
            raw: SettlementRawData,
            originalPaymentTxId: Long?,
            hostFee: Long,
            serviceFee: Long,
            settlementBaseDate: LocalDate,
            settlementPolicyId: Long,
            policyFeeRate: BigDecimal,
            policySettlementCycleDays: Int
        ): SettlementLedger {
            val isCancel = raw.transactionType == TransactionType.CANCEL
            val signedAmount = if (isCancel) -raw.amount else raw.amount
            val signedHostFee = if (isCancel) -hostFee else hostFee
            val signedServiceFee = if (isCancel) -serviceFee else serviceFee
            val signedTotalFee = signedHostFee + signedServiceFee

            return SettlementLedger(
                rawEventId = raw.eventId,
                merchantId = raw.merchantId,
                paymentKey = raw.paymentKey,
                transactionId = raw.transactionId,
                originalPaymentTxId = originalPaymentTxId,
                ledgerType = raw.transactionType,
                amount = signedAmount,
                hostFee = signedHostFee,
                serviceFee = signedServiceFee,
                totalFee = signedTotalFee,
                settlementAmount = signedAmount - signedTotalFee,
                occurredAt = raw.eventOccurredAt,
                settlementBaseDate = settlementBaseDate,
                settlementPolicyId = settlementPolicyId,
                policyFeeRate = policyFeeRate,
                policySettlementCycleDays = policySettlementCycleDays
            )
        }
    }
}
