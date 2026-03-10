package com.pg.worker.settlement.domain

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(
    name = "settlement_aggregate_items",
    indexes = [
        Index(name = "idx_item_aggregate_id", columnList = "aggregate_id"),
        Index(name = "idx_item_ledger_id", columnList = "ledger_id", unique = true)
    ]
)
class SettlementAggregateItem protected constructor(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "aggregate_id", nullable = false)
    val aggregateId: Long,

    @Column(name = "ledger_id", nullable = false, unique = true)
    val ledgerId: Long,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now()
) {
    companion object {
        fun create(aggregateId: Long, ledgerId: Long): SettlementAggregateItem {
            return SettlementAggregateItem(
                aggregateId = aggregateId,
                ledgerId = ledgerId
            )
        }
    }
}
