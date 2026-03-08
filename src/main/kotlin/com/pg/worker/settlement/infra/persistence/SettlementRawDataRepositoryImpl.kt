package com.pg.worker.settlement.infra.persistence

import com.pg.worker.settlement.application.repository.SettlementRawDataRepository
import com.pg.worker.settlement.domain.SettlementRawData
import com.pg.worker.settlement.domain.TransactionType
import org.springframework.stereotype.Repository

@Repository
class SettlementRawDataRepositoryImpl(
    private val jpaRepository: SettlementRawDataJpaRepository
) : SettlementRawDataRepository {

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
}
