package com.pg.worker.settlement.infra.persistence

import com.pg.worker.settlement.application.repository.PaymentTransactionRepository
import com.pg.worker.settlement.domain.PaymentTransaction
import com.pg.worker.settlement.domain.PaymentTxStatus
import com.pg.worker.settlement.domain.QPaymentTransaction
import com.querydsl.jpa.impl.JPAQueryFactory
import org.springframework.stereotype.Repository
import java.time.LocalDate

@Repository
class PaymentTransactionRepositoryImpl(
    private val jpaRepository: PaymentTransactionJpaRepository,
    private val queryFactory: JPAQueryFactory
) : PaymentTransactionRepository {

    private val qTx = QPaymentTransaction.paymentTransaction

    override fun findSuccessfulTransactions(date: LocalDate): List<PaymentTransaction> {
        val start = date.atStartOfDay()
        val end = date.plusDays(1).atStartOfDay()

        return queryFactory
            .selectFrom(qTx)
            .where(
                qTx.status.eq(PaymentTxStatus.SUCCESS),
                qTx.confirmedAt.goe(start),
                qTx.confirmedAt.lt(end)
            )
            .fetch()
    }

    override fun findAllByIdIn(ids: List<Long>): List<PaymentTransaction> {
        return jpaRepository.findAllById(ids)
    }
}
