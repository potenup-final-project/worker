package com.pg.worker.settlement.application.repository

import com.pg.worker.settlement.domain.ExternalTransactionRecord
import java.time.LocalDateTime

interface ExternalTransactionRecordRepository {
    fun save(record: ExternalTransactionRecord): ExternalTransactionRecord
    fun saveAll(records: List<ExternalTransactionRecord>): List<ExternalTransactionRecord>

    /** 외부 정산 파일 적재 시 중복 방지용 */
    fun existsByProviderTxId(providerTxId: String): Boolean

    /** 대사 엔진: 거래 발생일시 범위 기준 외부 거래 내역 전체 조회 (내부 RawData의 eventOccurredAt 범위와 동일하게 맞춤) */
    fun findAllByOccurredAtBetween(from: LocalDateTime, to: LocalDateTime): List<ExternalTransactionRecord>

    /** 단건 상세 조회 */
    fun findById(id: Long): ExternalTransactionRecord?
}
