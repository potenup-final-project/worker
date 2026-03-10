package com.pg.worker.settlement.application.repository

import com.pg.worker.settlement.application.repository.dto.ExternalTransactionDto
import java.time.LocalDate

/**
 * 외부 카드사/PG사 정산 내역 조회 클라이언트 인터페이스
 */
interface ExternalTransactionClient {
    fun fetchExternalTransactions(
        merchantId: Long? = null,
        baseDate: LocalDate
    ): List<ExternalTransactionDto>
}
