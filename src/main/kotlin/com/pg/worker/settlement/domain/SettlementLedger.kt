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
     * 승인 ledger면 승인 transaction ID,
     * 취소 ledger면 취소 transaction ID가 들어감.
     */
    @Column(name = "transaction_id", nullable = false, updatable = false)
    val transactionId: Long,

    /**
     * 취소 거래가 참조하는 원승인 거래 ID.
     * 승인 건은 null,
     * 취소 건은 어떤 승인 건을 취소한 것인지 연결하기 위해 사용.
     */
    @Column(name = "original_payment_tx_id")
    val originalPaymentTxId: Long? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "ledger_type", nullable = false, updatable = false)
    val ledgerType: TransactionType,

    /**
     * 정산 대상 원금 금액.
     * 승인 건은 양수, 취소 건은 음수로 저장하여 집계 시 sum(amount)만으로 순액 계산이 가능하도록 설계.
     */
    @Column(name = "amount", nullable = false, updatable = false)
    val amount: Long,

    /**
     * 해당 거래에 적용된 수수료 금액.
     * 정책 변경 이후에도 과거 정산 재현이 가능하도록
     * 계산 결과를 snapshot 형태로 저장.
     */
    @Column(name = "fee", nullable = false, updatable = false)
    val fee: Long,

    @Column(name = "settlement_amount", nullable = false, updatable = false)
    val settlementAmount: Long,

    @Column(name = "occurred_at", nullable = false, updatable = false)
    val occurredAt: LocalDateTime,

    /**
     * 이 거래가 어느 정산 기준일에 포함되는지 나타내는 값.
     * 예: D+2 정산, 영업일 기준 정산 등의 정책 계산 결과.
     * 가맹점 일자별 집계의 핵심 기준 컬럼.
     */
    @Column(name = "settlement_base_date", nullable = false, updatable = false)
    val settlementBaseDate: LocalDate,

    /**
     * ledger 계산 시 참조한 정산 정책 ID.
     * 어떤 정책을 기준으로 이 row가 만들어졌는지 추적하기 위해 저장.
     */
    @Column(name = "settlement_policy_id", nullable = false)
    val settlementPolicyId: Long,

    /**
     * ledger 생성 시점의 수수료율 snapshot.
     * 이후 정책이 바뀌더라도 과거 계산 근거를 재현할 수 있도록 저장.
     */
    @Column(name = "policy_fee_rate", nullable = false, precision = 5, scale = 4)
    val policyFeeRate: BigDecimal,

    /**
     * ledger 생성 시점의 정산 주기(snapshot).
     * 이후 정책이 바뀌어도 당시 어떤 주기로 계산됐는지 보존하기 위해 저장.
     */
    @Column(name = "policy_settlement_cycle_days", nullable = false)
    val policySettlementCycleDays: Int,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
) {
    companion object {
        fun create(
            raw: SettlementRawData,
            originalPaymentTxId: Long?,
            fee: Long,
            settlementAmount: Long,
            settlementBaseDate: LocalDate,
            policy: SettlementPolicy
        ): SettlementLedger = create(
            raw = raw,
            originalPaymentTxId = originalPaymentTxId,
            fee = fee,
            settlementAmount = settlementAmount,
            settlementBaseDate = settlementBaseDate,
            settlementPolicyId = policy.id,
            policyFeeRate = policy.feeRate,
            policySettlementCycleDays = policy.settlementCycleDays
        )

        fun create(
            raw: SettlementRawData,
            originalPaymentTxId: Long?,
            fee: Long,
            settlementAmount: Long,
            settlementBaseDate: LocalDate,
            settlementPolicyId: Long,
            policyFeeRate: BigDecimal,
            policySettlementCycleDays: Int
        ): SettlementLedger {
            return SettlementLedger(
                rawEventId = raw.eventId,
                merchantId = raw.merchantId,
                paymentKey = raw.paymentKey,
                transactionId = raw.transactionId,
                originalPaymentTxId = originalPaymentTxId,
                ledgerType = raw.transactionType,
                amount = if (raw.transactionType == TransactionType.CANCEL) -raw.amount else raw.amount,
                fee = if (raw.transactionType == TransactionType.CANCEL) -fee else fee,
                settlementAmount = if (raw.transactionType == TransactionType.CANCEL) -settlementAmount else settlementAmount,
                occurredAt = raw.eventOccurredAt,
                settlementBaseDate = settlementBaseDate,
                settlementPolicyId = settlementPolicyId,
                policyFeeRate = policyFeeRate,
                policySettlementCycleDays = policySettlementCycleDays
            )
        }
    }
}
