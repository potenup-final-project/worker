package com.pg.worker.settlement.application.service

import com.pg.worker.settlement.application.repository.SettlementRawDataRepository
import com.pg.worker.settlement.application.usecase.command.dto.RecordSettlementCommand
import com.pg.worker.settlement.domain.SettlementRawData
import com.pg.worker.settlement.domain.TransactionType
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Component
class SettlementRawDataWriter(
    private val rawDataRepository: SettlementRawDataRepository
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 원본 이벤트 저장 (독립 트랜잭션)
     * 멱등성 보장: (1) Select Pre-check로 불필요한 DB 부하 감소 (2) Unique Constraint로 레이스 컨디션 방어
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun write(command: RecordSettlementCommand): SettlementRawData? {
        // 1. 선조회로 멱등성 1차 확인 (이미 저장된 경우 무시)
        if (rawDataRepository.existsByEventId(command.eventId)) {
            log.warn("[Settlement] [Idempotent] 이미 저장된 Raw 이벤트입니다. eventId={}", command.eventId)
            return null
        }

        val type = TransactionType.entries.find { it.name == command.transactionType }
            ?: TransactionType.UNKNOWN // 파싱 실패 시 원본 보존을 위해 UNKNOWN으로 저장

        return try {
            val raw = SettlementRawData.create(
                eventId = command.eventId,
                paymentKey = command.paymentKey,
                transactionId = command.transactionId,
                orderId = command.orderId,
                providerTxId = command.providerTxId,
                merchantId = command.merchantId,
                transactionType = type,
                amount = command.amount,
                eventOccurredAt = command.eventOccurredAt
            )
            rawDataRepository.save(raw)
        } catch (e: DataIntegrityViolationException) {
            // 2. 동시성 이슈로 선조회를 통과한 경우, DB Unique 제약 조건으로 멱등성 최종 보장
            log.warn("[Settlement] [Idempotent] 중복 저장 시도 발생. DB 수준에서 무시됨. eventId={}", command.eventId)
            null
        }
    }
}
