package com.pg.worker.settlement.application.repository

import com.pg.worker.settlement.domain.ExternalSettlementDetail
import java.time.LocalDateTime

interface ExternalSettlementDetailRepository {
    fun saveAll(records: List<ExternalSettlementDetail>): List<ExternalSettlementDetail>

    /** 외부 정산 파일 적재 시 중복 방지용 */
    fun existsByProviderTxId(providerTxId: String): Boolean

    /** 대사 엔진: 거래 발생일시 범위 기준 외부 거래 내역 전체 조회 (내부 RawData의 eventOccurredAt 범위와 동일하게 맞춤) */
    fun findAllByOccurredAtBetween(from: LocalDateTime, to: LocalDateTime): List<ExternalSettlementDetail>

    /**
     * 특정 정산 기준일의 모든 외부 정산 내역 조회
     */
    fun findAllBySettlementBaseDate(baseDate: java.time.LocalDate): List<ExternalSettlementDetail>
}
