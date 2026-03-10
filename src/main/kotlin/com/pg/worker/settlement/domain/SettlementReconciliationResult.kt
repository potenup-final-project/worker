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
    name = "settlement_reconciliation_results",
    indexes = [
        Index(name = "idx_recon_date_status", columnList = "reconciliation_date, status"),
        Index(name = "idx_recon_merchant_date", columnList = "merchant_id, reconciliation_date"),
    ],
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_recon_provider_tx_id_date",
            columnNames = ["provider_tx_id", "reconciliation_date"]
        )
    ]
)
class SettlementReconciliationResult protected constructor(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "reconciliation_date", nullable = false, updatable = false)
    val reconciliationDate: LocalDate,

    @Column(name = "merchant_id")
    val merchantId: Long?,

    @Column(name = "provider_tx_id", length = 80, nullable = false, updatable = false)
    val providerTxId: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "result_type", length = 50, nullable = false)
    var resultType: SettlementReconciliationResultType,

    /** 매칭된 내부 RawData ID. 내부 누락(MISSING_INTERNAL)인 경우 null. */
    @Column(name = "internal_raw_data_id")
    val internalRawDataId: Long? = null,

    /** 매칭된 외부 Record ID. 외부 누락(MISSING_EXTERNAL)인 경우 null. */
    @Column(name = "external_record_id")
    val externalRecordId: Long? = null,

    /** 내부 RawData 기준 원금. 내부 누락 시 null. */
    @Column(name = "internal_amount")
    val internalAmount: Long? = null,

    /** 외부 정산 파일 기준 원금. 외부 누락 시 null. */
    @Column(name = "external_amount")
    val externalAmount: Long? = null,

    @Column(name = "amount_diff")
    val amountDiff: Long? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    var status: ReconciliationStatus = ReconciliationStatus.OPEN,

    /** 이상 원인 설명 또는 자동 해결 사유 */
    @Column(name = "reason", length = 500)
    var reason: String? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
) {
    fun resolve(reason: String) {
        this.status = ReconciliationStatus.RESOLVED
        this.reason = reason
        this.updatedAt = LocalDateTime.now()
    }

    fun ignore(reason: String) {
        this.status = ReconciliationStatus.IGNORED
        this.reason = reason
        this.updatedAt = LocalDateTime.now()
    }

    companion object {
        fun matched(
            reconciliationDate: LocalDate,
            merchantId: Long,
            providerTxId: String,
            internalRawDataId: Long,
            externalRecordId: Long,
            internalAmount: Long,
            externalAmount: Long,
        ): SettlementReconciliationResult = SettlementReconciliationResult(
            reconciliationDate = reconciliationDate,
            merchantId = merchantId,
            providerTxId = providerTxId,
            resultType = SettlementReconciliationResultType.MATCHED,
            internalRawDataId = internalRawDataId,
            externalRecordId = externalRecordId,
            internalAmount = internalAmount,
            externalAmount = externalAmount,
            amountDiff = 0L,
            status = ReconciliationStatus.RESOLVED,
        )

        fun mismatch(
            reconciliationDate: LocalDate,
            merchantId: Long?,
            providerTxId: String,
            resultType: SettlementReconciliationResultType,
            internalRawDataId: Long? = null,
            externalRecordId: Long? = null,
            internalAmount: Long? = null,
            externalAmount: Long? = null,
            reason: String? = null,
        ): SettlementReconciliationResult = SettlementReconciliationResult(
            reconciliationDate = reconciliationDate,
            merchantId = merchantId,
            providerTxId = providerTxId,
            resultType = resultType,
            internalRawDataId = internalRawDataId,
            externalRecordId = externalRecordId,
            internalAmount = internalAmount,
            externalAmount = externalAmount,
            amountDiff = if (externalAmount != null && internalAmount != null) externalAmount - internalAmount else null,
            status = ReconciliationStatus.OPEN,
            reason = reason,
        )
    }
}
