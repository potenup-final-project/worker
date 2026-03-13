package com.pg.worker.global.exception

import org.springframework.http.HttpStatus

enum class CommonErrorCode(
    override val httpStatus: HttpStatus,
    override val code: String,
    override val message: String,
) : ErrorCode {
    BASE_PROPERTIES_INVALID(HttpStatus.SERVICE_UNAVAILABLE,"EC-0001","환경 변수가 유효하지 않습니다."),
    RELAY_SQS_QUEUE_URL_MISSING(HttpStatus.INTERNAL_SERVER_ERROR, "EC-0002", "outbox relay SQS queue URL 설정이 필요합니다."),
}