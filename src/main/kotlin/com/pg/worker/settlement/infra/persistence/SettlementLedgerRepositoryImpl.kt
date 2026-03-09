package com.pg.worker.settlement.infra.persistence

import com.pg.worker.settlement.application.repository.SettlementLedgerRepository
import com.pg.worker.settlement.domain.QSettlementAggregateItem
import com.pg.worker.settlement.domain.QSettlementLedger
import com.pg.worker.settlement.domain.SettlementLedger
import com.querydsl.jpa.impl.JPAQueryFactory
import org.springframework.stereotype.Repository
import java.time.LocalDate

@Repository
class SettlementLedgerRepositoryImpl(
    private val jpaRepository: SettlementLedgerJpaRepository,
    private val queryFactory: JPAQueryFactory
) : SettlementLedgerRepository {

    private val qLedger = QSettlementLedger.settlementLedger
    private val qItem = QSettlementAggregateItem.settlementAggregateItem

    override fun save(ledger: SettlementLedger): SettlementLedger {
        return jpaRepository.save(ledger)
    }

    override fun findByRawEventId(rawEventId: String): SettlementLedger? {
        return jpaRepository.findByRawEventId(rawEventId)
    }

    override fun findAllByOriginalPaymentTxId(originalPaymentTxId: Long): List<SettlementLedger> {
        return jpaRepository.findAllByOriginalPaymentTxId(originalPaymentTxId)
    }

    override fun findMerchantIdsBySettlementBaseDate(baseDate: LocalDate): List<Long> {
        val startDateTime = baseDate.atStartOfDay()
        val endDateTime = baseDate.plusDays(1).atStartOfDay()

        return queryFactory
            .select(qLedger.merchantId)
            .from(qLedger)
            .leftJoin(qItem).on(qLedger.id.eq(qItem.ledgerId))
            .where(
                qLedger.settlementBaseDate.goe(startDateTime),
                qLedger.settlementBaseDate.lt(endDateTime),
                qItem.id.isNull
            )
            .distinct()
            .fetch()
    }

    override fun findUnaggregatedLedgers(merchantId: Long, baseDate: LocalDate): List<SettlementLedger> {
        val startDateTime = baseDate.atStartOfDay()
        val endDateTime = baseDate.plusDays(1).atStartOfDay()

        return queryFactory
            .selectFrom(qLedger)
            .leftJoin(qItem).on(qLedger.id.eq(qItem.ledgerId))
            .where(
                qLedger.merchantId.eq(merchantId),
                qLedger.settlementBaseDate.goe(startDateTime),
                qLedger.settlementBaseDate.lt(endDateTime),
                qItem.id.isNull
            )
            .fetch()
    }

    override fun findAllByTransactionId(transactionId: Long): List<SettlementLedger> {
        return jpaRepository.findAllByTransactionId(transactionId)
    }

    override fun findAllByTransactionIdIn(transactionIds: List<Long>): List<SettlementLedger> {
        return jpaRepository.findAllByTransactionIdIn(transactionIds)
    }
}
