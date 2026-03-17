package com.pg.worker.settlement.application.service

import com.pg.worker.settlement.application.repository.SettlementAggregateItemRepository
import com.pg.worker.settlement.application.repository.SettlementAggregateRepository
import com.pg.worker.settlement.application.repository.SettlementLedgerRepository
import com.pg.worker.settlement.domain.SettlementAggregate
import com.pg.worker.settlement.domain.SettlementAggregateItem
import com.pg.worker.settlement.domain.TransactionType
import com.gop.logging.contract.StructuredLogger
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

@Service
class SettlementAggregationService(
    private val ledgerRepository: SettlementLedgerRepository,
    private val aggregateRepository: SettlementAggregateRepository,
    private val aggregateItemRepository: SettlementAggregateItemRepository,
    private val log: StructuredLogger) {

    /**
     * 특정 가맹점의 특정 일자 정산 집계
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun aggregateForMerchant(merchantId: Long, baseDate: LocalDate) {
        // 1. 멱등성 확인: 이미 해당 날짜의 집계가 존재하는지 확인
        if (aggregateRepository.existsByMerchantIdAndSettlementBaseDate(merchantId, baseDate)) {
            log.info("[정산-집계] 이미 집계된 건입니다. merchantId={}, baseDate={}", merchantId, baseDate)
            return
        }

        // 2. 미집계 Ledger 조회
        val ledgers = ledgerRepository.findUnaggregatedLedgers(merchantId, baseDate)
        if (ledgers.isEmpty()) {
            return
        }

        // 3. 집계 계산
        var totalApprove = 0L
        var totalCancel = 0L
        var totalFee = 0L // 순수수료 (승인 수수료 + 취소 환급 수수료(음수))

        ledgers.forEach { ledger ->
            when (ledger.ledgerType) {
                TransactionType.APPROVE -> totalApprove += ledger.amount
                TransactionType.CANCEL -> totalCancel += kotlin.math.abs(ledger.amount)
                else -> {}
            }
            totalFee += ledger.totalFee
        }

        // 4. 집계 결과 저장
        val aggregate = SettlementAggregate.create(
            merchantId = merchantId,
            settlementBaseDate = baseDate,
            totalApproveAmount = totalApprove,
            totalCancelAmount = totalCancel,
            totalFeeAmount = totalFee,
            ledgerCount = ledgers.size
        )

        try {
            val savedAggregate = aggregateRepository.save(aggregate)

            // 5. 매핑 테이블 저장
            val items = ledgers.map { ledger ->
                SettlementAggregateItem.create(savedAggregate.id, ledger.id)
            }
            aggregateItemRepository.saveAll(items)

            log.info(
                "[정산-집계] 성공. merchantId={}, baseDate={}, count={}",
                merchantId, baseDate, ledgers.size
            )

        } catch (e: DataIntegrityViolationException) {
            // 실제 중복 여부를 명확히 체크하여 멱등성 보장
            if (aggregateRepository.existsByMerchantIdAndSettlementBaseDate(merchantId, baseDate)) {
                log.warn("[정산-집계] 중복 집계 시도 발생. 무시합니다. merchantId={}, baseDate={}", merchantId, baseDate)
            } else {
                log.error(
                    "[정산-집계] 예상치 못한 제약 조건 위반 발생. merchantId={}, baseDate={}, error={}",
                    merchantId, baseDate, e.message
                )
                throw e
            }
        }
    }
}
