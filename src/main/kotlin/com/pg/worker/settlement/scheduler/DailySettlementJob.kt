package com.pg.worker.settlement.scheduler

import com.pg.worker.settlement.application.repository.SettlementLedgerRepository
import com.pg.worker.settlement.application.service.SettlementAggregationService
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDate

@Component
class DailySettlementJob(
    private val ledgerRepository: SettlementLedgerRepository,
    private val aggregationService: SettlementAggregationService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 정산 집계 배치 (매일 새벽 2시 실행)
     * - 어제(`LocalDate.now().minusDays(1)`)까지 도래한 미집계 건들을 모두 처리
     */
    @Scheduled(cron = "0 0 2 * * *")
    fun runAggregation() {
        val targetDate = LocalDate.now().minusDays(1)
        log.info("[정산-집계-배치] 시작. 기준일: {}", targetDate)

        try {
            // 어제 날짜의 미집계 건이 있는 가맹점 목록 조회
            val merchantIds = ledgerRepository.findMerchantIdsBySettlementBaseDate(targetDate)
            log.info("[정산-집계-배치] 대상 가맹점 수: {}", merchantIds.size)

            // 가맹점별 집계 수행
            merchantIds.forEach { merchantId ->
                aggregationService.aggregateForMerchant(merchantId, targetDate)
            }

            log.info("[정산-집계-배치] 종료.")
        } catch (e: Exception) {
            log.error("[정산-집계-배치] 치명적 오류 발생", e)
        }
    }
}
