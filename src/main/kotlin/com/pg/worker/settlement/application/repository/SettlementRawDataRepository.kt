package com.pg.worker.settlement.application.repository

import com.pg.worker.settlement.domain.RawDataStatus
import com.pg.worker.settlement.domain.SettlementRawData
import com.pg.worker.settlement.domain.TransactionType
import java.time.LocalDateTime

interface SettlementRawDataRepository {
    fun save(data: SettlementRawData): SettlementRawData
    fun existsByEventId(eventId: String): Boolean
    fun findByPaymentKeyAndTransactionType(paymentKey: String, type: TransactionType): SettlementRawData?
    fun findById(id: Long): SettlementRawData?
    fun findByTransactionId(transactionId: Long): SettlementRawData?
    fun findAllByTransactionIdIn(transactionIds: List<Long>): List<SettlementRawData>
    fun findRetryableDataForClaim(statuses: List<RawDataStatus>, now: LocalDateTime, limit: Int): List<SettlementRawData>
    fun findStuckProcessingDataForClaim(threshold: LocalDateTime, limit: Int): List<SettlementRawData>

    /**
     * 대사 엔진 Step 2: 거래 발생 시각 범위 기준 내부 데이터 bulk 조회.
     * 기준일(T-1) 00:00 ~ 23:59:59 범위로 호출.
     */
    fun findAllByEventOccurredAtBetween(from: LocalDateTime, to: LocalDateTime): List<SettlementRawData>

    /**
     * 대사 엔진 보조: 외부 providerTxId 목록 기준 내부 데이터 bulk 조회.
     * MISSING_INTERNAL 탐지 시 외부 키 목록으로 내부 존재 여부 확인에 사용.
     */
    fun findAllByProviderTxIdIn(providerTxIds: List<String>): List<SettlementRawData>
}
