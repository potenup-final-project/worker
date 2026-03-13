package com.pg.worker.settlement.domain

/**
 * 정산 집계(Aggregate)의 처리 상태
 */
enum class SettlementAggregateStatus {
    READY,              // 집계 완료, 지급 대기 중
    PAYOUT_REQUESTED,   // 지급 요청됨 (은행/펌뱅킹)
    COMPLETED,          // 지급 완료
    FAILED              // 지급 실패
}
