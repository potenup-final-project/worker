package com.pg.worker.settlement.scheduler

import com.pg.worker.settlement.application.service.SettlementReconciliationEngine
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDate

@Component
class ExternalReconciliationJob(
    private val reconciliationEngine: SettlementReconciliationEngine,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 매일 새벽 3시 30분에 전날(T-1) 외부 대사를 수행한다.
     *
     * 실행 순서:
     *   02:00 - DailySettlementJob        (정산 집계)
     *   03:00 - InternalReconciliationJob (내부 대사)
     *   03:30 - SettlementReconciliationJob (외부 대사) ← 여기
     *
     * 내부 대사(03:00) 완료 이후 실행함으로써
     * 내부 원장이 모두 정리된 상태에서 외부 대사를 수행한다.
     *
     * 대사 기준일: LocalDate.now().minusDays(1) (T-1 전일자)
     * 예) 3월 10일 03:30 실행 → 3월 9일 거래 대상
     */
    @Scheduled(cron = "0 30 3 * * *")
    fun runDailyExternalReconciliation() {
        val targetDate = LocalDate.now().minusDays(1)
        log.info("[외부대사-배치] 시작. 대상일={}", targetDate)

        try {
            reconciliationEngine.reconcile(targetDate)
            log.info("[외부대사-배치] 완료. 대상일={}", targetDate)
        } catch (e: Exception) {
            log.error("[외부대사-배치] 실패. 대상일={}", targetDate, e)
        }
    }
}
