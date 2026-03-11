package com.pg.worker.settlement.infra.client

import com.pg.worker.settlement.application.repository.ExternalSettlementClient
import com.pg.worker.settlement.application.repository.dto.ExternalSettlementDetailDto
import com.pg.worker.settlement.domain.TransactionType
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * 테스트용 카드사 API Mock 클라이언트 (테스트 케이스 확장 버전)
 */
@Component
class MockExternalSettlementClient : ExternalSettlementClient {
    override fun fetchExternalTransactions(merchantId: Long?, baseDate: LocalDate): List<ExternalSettlementDetailDto> {
        val dateStr = baseDate.toString().replace("-", "")
        val feeRate = 0.021 // 카드사 원가 수수료 2.1%
        
        return listOf(
            // 001: 정상 매칭용 (10,000원, 수수료 210원)
            createMockDto("MOCK_TX_${dateStr}_001", 1001L, TransactionType.APPROVE, 10000L, feeRate, baseDate),
            
            // 002: 취소 매칭용 (2,000원, 수수료 42원)
            createMockDto("MOCK_TX_${dateStr}_002", 1001L, TransactionType.CANCEL, 2000L, feeRate, baseDate),
            
            // 003: 수수료 불일치 테스트용 (외부는 210원, 내부 원장은 300원으로 세팅할 것)
            createMockDto("MOCK_TX_${dateStr}_003", 1001L, TransactionType.APPROVE, 10000L, feeRate, baseDate),
            
            // 004: 원금 불일치 테스트용 (외부는 10,000원, 내부 원장은 5,000원으로 세팅할 것)
            createMockDto("MOCK_TX_${dateStr}_004", 1001L, TransactionType.APPROVE, 10000L, feeRate, baseDate)
        )
    }

    private fun createMockDto(
        providerTxId: String,
        merchantId: Long,
        type: TransactionType,
        amount: Long,
        feeRate: Double,
        baseDate: LocalDate
    ): ExternalSettlementDetailDto {
        val fee = (amount * feeRate).toLong()
        return ExternalSettlementDetailDto(
            providerTxId = providerTxId,
            sourceSystem = "MOCK_CARD_SYSTEM",
            merchantId = merchantId,
            transactionType = type,
            amount = amount,
            fee = fee,
            netAmount = amount - fee,
            settlementBaseDate = baseDate,
            payoutDate = baseDate.plusDays(2),
            occurredAt = baseDate.minusDays(1).atTime(10, 0, 0)
        )
    }
}
