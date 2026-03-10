package com.pg.worker.settlement.application.repository.dto

import com.pg.worker.settlement.domain.TransactionType
import java.time.LocalDateTime

/**
 * 외부 카드사/PG사로부터 수신한 거래 내역 DTO
 */
data class ExternalTransactionDto(
    val providerTxId: String,
    val sourceSystem: String,
    val merchantId: Long,
    val transactionType: TransactionType,
    val amount: Long,
    val occurredAt: LocalDateTime,
    val rawPayload: String? = null
)
