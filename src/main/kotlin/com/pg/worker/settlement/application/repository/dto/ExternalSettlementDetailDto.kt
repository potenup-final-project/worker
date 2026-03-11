package com.pg.worker.settlement.application.repository.dto

import com.pg.worker.settlement.domain.TransactionType
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 외부 카드사/PG사로부터 수신한 정산 상세 내역 DTO
 */
data class ExternalSettlementDetailDto(
    val providerTxId: String,
    val sourceSystem: String,
    val merchantId: Long,
    val transactionType: TransactionType,
    val amount: Long,
    val fee: Long,
    val netAmount: Long,
    val settlementBaseDate: LocalDate,
    val payoutDate: LocalDate,
    val occurredAt: LocalDateTime,
    val rawPayload: String? = null
)
