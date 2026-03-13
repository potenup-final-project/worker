package com.pg.worker.settlement.domain

import jakarta.persistence.*
import java.time.LocalDate
import java.time.LocalDateTime

@Entity
@Table(
    name = "internal_reconciliation_results",
    indexes = [
        Index(name = "idx_recon_date_status", columnList = "reconciliation_date, status"),
    ],
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_recon_tx_mismatch_status",
            columnNames = ["transaction_id", "mismatch_type", "status"]
        )
    ]
)
class InternalReconciliationResult(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "reconciliation_date", nullable = false, updatable = false)
    val reconciliationDate: LocalDate,

    @Column(name = "merchant_id", nullable = false, updatable = false)
    val merchantId: Long,

    @Column(name = "payment_id", nullable = false, updatable = false)
    val paymentId: Long,

    @Column(name = "transaction_id", nullable = false, updatable = false)
    val transactionId: Long,

    /**
     * 매칭된 정산 원장 ID. 
     * 누락 시에는 null이며, 해결(RESOLVED) 시 채워진다.
     */
    @Column(name = "settlement_ledger_id")
    var settlementLedgerId: Long? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "mismatch_type", length = 50, nullable = false)
    var mismatchType: MismatchType,

    @Column(name = "reason", length = 500)
    var reason: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    var status: ReconciliationStatus = ReconciliationStatus.OPEN,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now()
) {
    fun resolve(ledgerId: Long, reason: String? = null) {
        this.status = ReconciliationStatus.RESOLVED
        this.settlementLedgerId = ledgerId
        this.updatedAt = LocalDateTime.now()
        if (reason != null) this.reason = reason
    }

    fun transitionTo(newType: MismatchType, reason: String) {
        this.mismatchType = newType
        this.reason = reason
        this.updatedAt = LocalDateTime.now()
    }
}

enum class MismatchType {
    MISSING_RAW_DATA,
    MISSING_LEDGER,
    DUPLICATED_LEDGER,
    AMOUNT_MISMATCH,
    TYPE_MISMATCH,
}

enum class ReconciliationStatus {
    OPEN,
    RESOLVED,
    IGNORED
}
