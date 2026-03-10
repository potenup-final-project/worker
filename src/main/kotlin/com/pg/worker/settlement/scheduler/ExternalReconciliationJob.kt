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
