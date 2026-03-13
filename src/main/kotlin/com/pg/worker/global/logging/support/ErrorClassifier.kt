package com.pg.worker.global.logging.support

import com.pg.worker.global.exception.BusinessException

object ErrorClassifier {
    fun classify(e: Exception): String {
        return when (e) {
            is IllegalArgumentException -> "VALIDATION_ERROR"
            is IllegalStateException -> "STATE_ERROR"
            is BusinessException -> "BUSINESS_ERROR"
            else -> "SYSTEM_ERROR"
        }
    }
}
