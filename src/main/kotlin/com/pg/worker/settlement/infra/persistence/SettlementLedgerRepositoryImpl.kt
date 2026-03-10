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
        return queryFactory
            .select(qLedger.merchantId)
            .from(qLedger)
            .leftJoin(qItem).on(qLedger.id.eq(qItem.ledgerId))
            .where(
                qLedger.settlementBaseDate.eq(baseDate),
                qItem.id.isNull
            )
            .distinct()
            .fetch()
    }

    override fun findUnaggregatedLedgers(merchantId: Long, baseDate: LocalDate): List<SettlementLedger> {
        return queryFactory
            .selectFrom(qLedger)
            .leftJoin(qItem).on(qLedger.id.eq(qItem.ledgerId))
            .where(
                qLedger.merchantId.eq(merchantId),
                qLedger.settlementBaseDate.eq(baseDate),
                qItem.id.isNull
            )
            .fetch()
    }

    override fun findLatestByTransactionId(transactionId: Long): SettlementLedger? {
        return queryFactory
            .selectFrom(qLedger)
            .where(qLedger.transactionId.eq(transactionId))
            .orderBy(qLedger.id.desc())
            .fetchFirst()
    }

    override fun findAllByTransactionIdIn(transactionIds: List<Long>): List<SettlementLedger> {
        return jpaRepository.findAllByTransactionIdIn(transactionIds)
    }
}
