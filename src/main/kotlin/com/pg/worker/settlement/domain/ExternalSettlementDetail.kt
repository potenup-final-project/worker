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
import jakarta.persistence.UniqueConstraint
import java.time.LocalDate
import java.time.LocalDateTime

@Entity
@Table(
    name = "external_settlement_details",
    indexes = [
        Index(name = "idx_ext_occurred_at_merchant", columnList = "occurred_at, merchant_id"),
    ],
    uniqueConstraints = [
        UniqueConstraint(name = "uq_ext_provider_tx_id", columnNames = ["provider_tx_id"])
    ]
)
class ExternalSettlementDetail protected constructor(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "provider_tx_id", length = 80, nullable = false, unique = true, updatable = false)
    val providerTxId: String,

    /**
     * 외부 정산 데이터 출처 구분 (예: "NICE", "KCP", "TOSS").
     * 1차에서는 단순 String으로 관리.
     */
    @Column(name = "source_system", length = 50, nullable = false, updatable = false)
    val sourceSystem: String,

    @Column(name = "merchant_id", nullable = false, updatable = false)
    val merchantId: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", length = 20, nullable = false, updatable = false)
    val transactionType: TransactionType,

    @Column(name = "amount", nullable = false, updatable = false)
    val amount: Long,

    @Column(name = "fee", nullable = false, updatable = false)
    val fee: Long,

    @Column(name = "net_amount", nullable = false, updatable = false)
    val netAmount: Long,

    @Column(name = "settlement_base_date", nullable = false, updatable = false)
    val settlementBaseDate: LocalDate,

    @Column(name = "payout_date", nullable = false, updatable = false)
    val payoutDate: LocalDate,

    @Column(name = "occurred_at", nullable = false, updatable = false)
    val occurredAt: LocalDateTime,

    @Column(name = "raw_payload", columnDefinition = "TEXT")
    val rawPayload: String? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
) {
    companion object {
        fun create(
            providerTxId: String,
            sourceSystem: String,
            merchantId: Long,
            transactionType: TransactionType,
            amount: Long,
            fee: Long,
            netAmount: Long,
            settlementBaseDate: LocalDate,
            payoutDate: LocalDate,
            occurredAt: LocalDateTime,
            rawPayload: String? = null,
        ): ExternalSettlementDetail = ExternalSettlementDetail(
            providerTxId = providerTxId,
            sourceSystem = sourceSystem,
            merchantId = merchantId,
            transactionType = transactionType,
            amount = amount,
            fee = fee,
            netAmount = netAmount,
            settlementBaseDate = settlementBaseDate,
            payoutDate = payoutDate,
            occurredAt = occurredAt,
            rawPayload = rawPayload,
        )
    }
}
