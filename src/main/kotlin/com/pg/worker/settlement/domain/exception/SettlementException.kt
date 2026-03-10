package com.pg.worker.settlement.domain.exception

/**
 * 정산 도메인 최상위 예외
 */
sealed class SettlementException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * 선행 의존성 미충족 (예: 취소가 승인보다 먼저 도착)
 * 정책: PENDING_DEPENDENCY 상태로 변경 후 일정 시간 뒤 재시도
 */
class PendingDependencyException(message: String) : SettlementException(message)

/**
 * 비즈니스/데이터 정합성 오류 (예: 정책 미설정, 잘못된 데이터 입력)
 * 정책: FAILED_NON_RETRYABLE 상태로 변경. 재시도해도 성공 가능성 없음 (운영자 개입 필요)
 */
class NonRetryableException(message: String, cause: Throwable? = null) : SettlementException(message, cause)

/**
 * 기술적 일시 장애 (예: DB 락 충돌, 네트워크 타임아웃, 일시적 커넥션 오류)
 * 정책: FAILED_RETRYABLE 상태로 변경. 지연 시간 후 자동 재시도
 */
class RetryableException(message: String, cause: Throwable? = null) : SettlementException(message, cause)
