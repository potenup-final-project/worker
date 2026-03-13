package com.pg.worker.settlement.domain

import jakarta.persistence.*
import org.hibernate.annotations.Immutable
import java.time.LocalDateTime

@Entity
@Immutable
@Table(name = "payment_transactions")
class PaymentTransaction(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "tx_id", nullable = false, updatable = false)
    val id: Long = 0,

    @Column(name = "payment_id", nullable = false, updatable = false)
    val paymentId: Long,

    @Column(name = "merchant_id", nullable = false, updatable = false)
    val merchantId: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 10, updatable = false)
    val type: PaymentTxType,

    @Enumerated(EnumType.STRING)
    @Column(name = "tx_status", nullable = false, length = 10)
    val status: PaymentTxStatus,

    @Column(name = "requested_amount", nullable = false, updatable = false)
    val requestedAmount: Long,

    @Column(name = "pg_tx_id", length = 80)
    val providerTxId: String? = null,

    @Column(name = "idempotency_key", length = 80, nullable = false, updatable = false)
    val idempotencyKey: String,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime,

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_code", length = 30)
    val failureCode: PaymentTxFailureCode? = null,

    @Column(name = "failure_message", length = 255)
    val failureMessage: String? = null,

    @Column(name = "need_net_cancel", nullable = false)
    val needNetCancel: Boolean = false,

    @Column(name = "need_reconciliation", nullable = false)
    val needReconciliation: Boolean = false,

    @Column(name = "attempt_count", nullable = false)
    val attemptCount: Int = 0,

    @Column(name = "next_attempt_at")
    val nextAttemptAt: LocalDateTime? = null,

    /**
     * 실제 거래 성공 시각.
     * 대사 배치 시 '성공한 거래'를 선별하는 기준 일자로 사용됨.
     */
    @Column(name = "confirmed_at")
    val confirmedAt: LocalDateTime? = null,

    @Column(name = "updated_at", nullable = false)
    val updatedAt: LocalDateTime,
)

enum class PaymentTxType {
    APPROVE, CANCEL
}

fun PaymentTxType.toLedgerType(): TransactionType = when (this) {
    PaymentTxType.APPROVE -> TransactionType.APPROVE
    PaymentTxType.CANCEL -> TransactionType.CANCEL
}

enum class PaymentTxStatus {
    PENDING, SUCCESS, FAIL, UNKNOWN
}

enum class PaymentTxFailureCode {
    CARD_BLOCKED,
    CARD_EXPIRED,
    INSUFFICIENT_FUNDS,
    LIMIT_EXCEEDED,
    INVALID_CARD,
    INVALID_PIN_OR_CVC,
    FRAUD_SUSPECTED,
    MERCHANT_NOT_ALLOWED,
    DUPLICATE_REQUEST,
    INTERNAL_ERROR,
    NET_CANCEL_DONE,
    PROVIDER_SUCCESS_LOCAL_FAIL,
}
