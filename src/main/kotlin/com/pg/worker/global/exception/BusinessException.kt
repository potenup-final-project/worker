package com.pg.worker.global.exception

import com.gop.logging.contract.CodedError
import org.springframework.http.HttpStatus

open class BusinessException(
    override val code: String,
    override val httpStatus: HttpStatus,
    override val message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause), ErrorCode, CodedError {

    override val errorCode: String get() = code

    constructor(
        errorCode: ErrorCode,
        messageMapper: ((String) -> String)? = null,
        cause: Throwable? = null
    ) : this(
        code = errorCode.code,
        httpStatus = errorCode.httpStatus,
        message = messageMapper?.let { messageMapper(errorCode.message) } ?: errorCode.message,
        cause = cause
    )
}
