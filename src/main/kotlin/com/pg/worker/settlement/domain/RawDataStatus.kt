package com.pg.worker.settlement.domain

/**
 * raw 이벤트의 현재 처리 상태.
 * - RECEIVED: 처음 수신됨
 * - PENDING_DEPENDENCY: 선행 데이터가 없어 대기 중
 * - PROCESSING: 현재 처리 중
 * - PROCESSED: ledger 반영까지 완료
 * - FAILED_RETRYABLE: 재시도 가능한 실패
 * - FAILED_NON_RETRYABLE: 재시도 불가능한 실패
 */
enum class RawDataStatus {
    RECEIVED,
    PENDING_DEPENDENCY,
    PROCESSING,
    PROCESSED,
    FAILED_RETRYABLE,
    FAILED_NON_RETRYABLE
}
