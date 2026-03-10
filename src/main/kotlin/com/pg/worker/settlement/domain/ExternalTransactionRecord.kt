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

/**
 * 외부 카드사/PG사 정산 파일을 DB에 적재하는 raw 저장소.
 *
 * 1차에서는 CSV 파서 없이 API 또는 테스트 데이터로 직접 적재 가능한 모델.
 * [providerTxId]가 내부 [SettlementRawData]와의 매칭 핵심 키다.
 */
@Entity
@Table(
    name = "external_settlement_records",
    indexes = [
        Index(name = "idx_ext_occurred_at_merchant", columnList = "occurred_at, merchant_id"),
    ],
    uniqueConstraints = [
        UniqueConstraint(name = "uq_ext_provider_tx_id", columnNames = ["provider_tx_id"])
    ]
)
class ExternalTransactionRecord protected constructor(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    /**
     * 카드사/PG사 측 거래 식별자 (카드 승인번호, 매입번호 등).
     * 내부 [SettlementRawData.providerTxId]와 매칭되는 핵심 키.
     */
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

    /**
     * 카드사 기준 거래 원금.
     * 내부 [SettlementRawData.amount]와 비교하는 핵심 비교 항목.
     */
    @Column(name = "amount", nullable = false, updatable = false)
    val amount: Long,

    /**
     * 카드사 기준 정산 기준일.
     * 대사 배치에서 날짜 기준 데이터 조회 시 사용.
     */
    @Column(name = "settlement_base_date", nullable = false, updatable = false)
    val settlementBaseDate: LocalDate,

    /**
     * 카드사 기준 거래 발생일시.
     */
    @Column(name = "occurred_at", nullable = false, updatable = false)
    val occurredAt: LocalDateTime,

    /**
     * 원본 파일 내용 보관용 (디버깅 및 감사 추적용).
     * nullable — 파일 전체를 저장할 필요가 없는 경우 생략 가능.
     */
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
            settlementBaseDate: LocalDate,
            occurredAt: LocalDateTime,
            rawPayload: String? = null,
        ): ExternalTransactionRecord = ExternalTransactionRecord(
            providerTxId = providerTxId,
            sourceSystem = sourceSystem,
            merchantId = merchantId,
            transactionType = transactionType,
            amount = amount,
            settlementBaseDate = settlementBaseDate,
            occurredAt = occurredAt,
            rawPayload = rawPayload,
        )
    }
}
