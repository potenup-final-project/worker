package com.pg.worker.settlement.scheduler

import com.pg.worker.settlement.application.repository.SettlementLedgerRepository
import com.pg.worker.settlement.application.service.SettlementAggregationService
import com.pg.worker.global.logging.context.TraceScope
import com.gop.logging.contract.StructuredLogger
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDate

@Component
class DailySettlementJob(
    private val ledgerRepository: SettlementLedgerRepository,
    private val aggregationService: SettlementAggregationService,
    private val log: StructuredLogger) {

    /**
     * 정산 집계 배치 (매일 새벽 2시 실행)
     * - 어제(`LocalDate.now().minusDays(1)`)까지 도래한 미집계 건들을 모두 처리
     */
    @Scheduled(cron = "0 0 2 * * *", scheduler = "settlementWorkerScheduler")
    fun runAggregation() {
        val runTraceId = TraceScope.newRunTraceId("worker-settlement-daily")
        TraceScope.withTraceContext(traceId = runTraceId, messageId = "daily-settlement-job") {
            val targetDate = LocalDate.now().minusDays(1)
            log.info("[정산-집계-배치] 시작. 기준일: {}", targetDate)

            try {
                val merchantIds = ledgerRepository.findMerchantIdsBySettlementBaseDate(targetDate)
                log.info("[정산-집계-배치] 대상 가맹점 수: {}", merchantIds.size)

                var successCount = 0
                var failCount = 0
                merchantIds.forEach { merchantId ->
                    try {
                        TraceScope.withTraceContext(
                            traceId = runTraceId,
                            messageId = "daily-settlement-job",
                            eventId = merchantId.toString()
                        ) {
                            aggregationService.aggregateForMerchant(merchantId, targetDate)
                        }
                        successCount++
                    } catch (e: Exception) {
                        failCount++
                        log.error("[정산-집계-배치] 가맹점 집계 실패. merchantId={}, baseDate={}", merchantId, targetDate, e)
                    }
                }
                log.info("[정산-집계-배치] 처리 결과. 성공={}, 실패={}", successCount, failCount)
                log.info("[정산-집계-배치] 종료.")
            } catch (e: Exception) {
                log.error("[정산-집계-배치] 치명적 오류 발생", e)
            }
        }
    }
}
