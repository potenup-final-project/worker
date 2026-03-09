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
import java.time.LocalDateTime

@Entity
@Table(
    name = "settlement_raw_data",
    indexes = [
        Index(name = "idx_raw_event_id", columnList = "event_id", unique = true),
        Index(name = "idx_raw_status_retry", columnList = "status, next_retry_at"),
        Index(name = "idx_raw_payment_key", columnList = "payment_key")
    ]
)
class SettlementRawData protected constructor(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "event_id", nullable = false, unique = true, updatable = false)
    val eventId: String,

    @Column(name = "payment_key", length = 80, nullable = false, updatable = false)
    val paymentKey: String,

    @Column(name = "transaction_id", nullable = false, updatable = false)
    val transactionId: Long,

    /**
     * 주문 단위를 식별하는 비즈니스 ID.
     * 결제/정산 조회 시 paymentKey와 함께 상위 비즈니스 문맥 추적에 사용된다.
     */
    @Column(name = "order_id", length = 80, nullable = false, updatable = false)
    val orderId: String,

    /**
     * 외부 카드사/프로바이더 측 거래 식별자.
     * 외부 시스템과 대사하거나 원거래를 추적할 때 사용한다.
     */
    @Column(name = "provider_tx_id", length = 80, nullable = false, updatable = false)
    val providerTxId: String,

    @Column(name = "merchant_id", nullable = false, updatable = false)
    val merchantId: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false, updatable = false)
    val transactionType: TransactionType,

    @Column(name = "amount", nullable = false, updatable = false)
    val amount: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: RawDataStatus = RawDataStatus.RECEIVED,

    @Column(name = "event_occurred_at", nullable = false, updatable = false)
    val eventOccurredAt: LocalDateTime,

    @Column(name = "retry_count", nullable = false)
    var retryCount: Int = 0,

    @Column(name = "next_retry_at")
    var nextRetryAt: LocalDateTime? = null,

    @Column(name = "last_tried_at")
    var lastTriedAt: LocalDateTime? = null,

    @Column(name = "failure_reason", length = 500)
    var failureReason: String? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
) {
    companion object {
        fun create(
            eventId: String, paymentKey: String, transactionId: Long, orderId: String,
            providerTxId: String, merchantId: Long, transactionType: TransactionType,
            amount: Long, eventOccurredAt: LocalDateTime
        ): SettlementRawData {
            return SettlementRawData(
                eventId = eventId,
                paymentKey = paymentKey,
                transactionId = transactionId,
                orderId = orderId,
                providerTxId = providerTxId,
                merchantId = merchantId,
                transactionType = transactionType,
                amount = amount,
                eventOccurredAt = eventOccurredAt
            )
        }
    }

    fun markProcessed() {
        this.status = RawDataStatus.PROCESSED
        this.lastTriedAt = LocalDateTime.now()
        this.failureReason = null
        this.nextRetryAt = null
    }

    fun markPendingDependency(reason: String, nextRetryAt: LocalDateTime) {
        this.status = RawDataStatus.PENDING_DEPENDENCY
        this.failureReason = reason
        this.nextRetryAt = nextRetryAt
        this.lastTriedAt = LocalDateTime.now()
    }

    fun markFailedRetryable(reason: String, nextRetryAt: LocalDateTime) {
        this.status = RawDataStatus.FAILED_RETRYABLE
        this.failureReason = reason
        this.retryCount++
        this.nextRetryAt = nextRetryAt
        this.lastTriedAt = LocalDateTime.now()
    }

    fun markFailedNonRetryable(reason: String) {
        this.status = RawDataStatus.FAILED_NON_RETRYABLE
        this.failureReason = reason
        this.lastTriedAt = LocalDateTime.now()
        this.nextRetryAt = null
    }
}
