package com.pg.worker.settlement.domain.exception

import com.pg.worker.global.exception.ErrorCode
import org.springframework.http.HttpStatus

enum class SettlementErrorCode(
    override val httpStatus: HttpStatus,
    override val code: String,
    override val message: String
) : ErrorCode {
    PAYMENT_TRANSACTION_NOT_FOUND(HttpStatus.NOT_FOUND, "STL-0001", "결제 거래를 찾을 수 없습니다."),
    RECONCILIATION_RESULT_NOT_FOUND(HttpStatus.NOT_FOUND, "STL-0002", "대사 결과를 찾을 수 없습니다."),
}
