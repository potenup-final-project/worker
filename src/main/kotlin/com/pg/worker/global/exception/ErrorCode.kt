package com.pg.worker.global.exception

import org.springframework.http.HttpStatus

interface ErrorCode {
    val httpStatus: HttpStatus
    val code: String
    val message: String
}
