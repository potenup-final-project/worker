package com.pg.worker.settlement.infra.persistence

import com.pg.worker.settlement.application.repository.SettlementRawDataRepository
import com.pg.worker.settlement.domain.QSettlementRawData
import com.pg.worker.settlement.domain.RawDataStatus
import com.pg.worker.settlement.domain.SettlementRawData
import com.pg.worker.settlement.domain.TransactionType
import com.querydsl.jpa.impl.JPAQueryFactory
import jakarta.persistence.LockModeType
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Repository
class SettlementRawDataRepositoryImpl(
    private val jpaRepository: SettlementRawDataJpaRepository,
    private val queryFactory: JPAQueryFactory
) : SettlementRawDataRepository {

    private val qRaw = QSettlementRawData.settlementRawData

    override fun save(data: SettlementRawData): SettlementRawData {
        return jpaRepository.save(data)
    }

    override fun existsByEventId(eventId: String): Boolean {
        return jpaRepository.existsByEventId(eventId)
    }

    override fun findByPaymentKeyAndTransactionType(paymentKey: String, type: TransactionType): SettlementRawData? {
        return jpaRepository.findByPaymentKeyAndTransactionType(paymentKey, type)
    }

    override fun findById(id: Long): SettlementRawData? {
        return jpaRepository.findById(id).orElse(null)
    }

    override fun findByTransactionId(transactionId: Long): SettlementRawData? {
        return jpaRepository.findByTransactionId(transactionId)
    }

    override fun findAllByTransactionIdIn(transactionIds: List<Long>): List<SettlementRawData> {
        return jpaRepository.findAllByTransactionIdIn(transactionIds)
    }

    override fun findRetryableDataForClaim(
        statuses: List<RawDataStatus>,
        now: LocalDateTime,
        limit: Int
    ): List<SettlementRawData> {
        return queryFactory
            .selectFrom(qRaw)
            .where(
                qRaw.status.`in`(statuses),
                qRaw.nextRetryAt.loe(now)
            )
            .orderBy(qRaw.nextRetryAt.asc(), qRaw.id.asc())
            .limit(limit.toLong())
            .setLockMode(LockModeType.PESSIMISTIC_WRITE)
            .setHint(JPA_LOCK_TIMEOUT_HINT, SKIP_LOCKED)
            .fetch()
    }

    override fun findStuckProcessingDataForClaim(threshold: LocalDateTime, limit: Int): List<SettlementRawData> {
        return queryFactory
            .selectFrom(qRaw)
            .where(
                qRaw.status.eq(RawDataStatus.PROCESSING),
                qRaw.claimedAt.loe(threshold)
            )
            .orderBy(qRaw.claimedAt.asc(), qRaw.id.asc())
            .limit(limit.toLong())
            .setLockMode(LockModeType.PESSIMISTIC_WRITE)
            .setHint(JPA_LOCK_TIMEOUT_HINT, SKIP_LOCKED)
            .fetch()
    }

    companion object {
        private const val JPA_LOCK_TIMEOUT_HINT = "jakarta.persistence.lock.timeout"
        private const val SKIP_LOCKED = -2
    }
}
