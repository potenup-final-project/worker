package com.pg.worker.settlement.domain

enum class TransactionType { 
    APPROVE, 
    CANCEL,
    UNKNOWN // 잘못된 타입 수신 시 원본 보존을 위해 사용
}
