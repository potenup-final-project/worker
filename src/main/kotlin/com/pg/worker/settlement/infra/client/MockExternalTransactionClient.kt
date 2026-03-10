package com.pg.worker.settlement.infra.client

import com.pg.worker.settlement.application.repository.ExternalTransactionClient
import com.pg.worker.settlement.application.repository.dto.ExternalTransactionDto
import com.pg.worker.settlement.domain.TransactionType
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * 테스트용 카드사 API Mock 클라이언트
 */
@Component
class MockExternalTransactionClient : ExternalTransactionClient {
    override fun fetchExternalTransactions(merchantId: Long?, baseDate: LocalDate): List<ExternalTransactionDto> {
        // 테스트를 위해 고정된 데이터를 반환 (실제로는 API 호출 로직이 들어갈 자리)
        val dateStr = baseDate.toString().replace("-", "")
        return listOf(
            ExternalTransactionDto(
                providerTxId = "MOCK_TX_${dateStr}_001",
                sourceSystem = "MOCK_CARD_SYSTEM",
                merchantId = 1001L,
                transactionType = TransactionType.APPROVE,
                amount = 10000L,
                occurredAt = baseDate.atTime(10, 0, 0)
            ),
            ExternalTransactionDto(
                providerTxId = "MOCK_TX_${dateStr}_002",
                sourceSystem = "MOCK_CARD_SYSTEM",
                merchantId = 1001L,
                transactionType = TransactionType.CANCEL,
                amount = 2000L,
                occurredAt = baseDate.atTime(11, 30, 0)
            )
        )
    }
}
