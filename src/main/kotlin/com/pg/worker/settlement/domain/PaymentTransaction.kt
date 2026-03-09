package com.pg.worker.settlement.domain

import jakarta.persistence.*
import org.hibernate.annotations.Immutable
import java.time.LocalDateTime

@Entity
@Immutable
@Table(name = "payment_transactions")
class PaymentTransaction(
    @Id
    @Column(name = "tx_id")
    val id: Long,

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

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime,

    /**
     * 실제 거래 성공 시각. 
     * 대사 배치 시 '성공한 거래'를 선별하는 기준 일자로 사용됨.
     */
    @Column(name = "confirmed_at")
    val confirmedAt: LocalDateTime? = null
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
