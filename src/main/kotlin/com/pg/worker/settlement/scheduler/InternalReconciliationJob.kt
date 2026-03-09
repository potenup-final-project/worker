package com.pg.worker.settlement.scheduler

import com.pg.worker.settlement.application.service.InternalReconciliationService
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDate

@Component
class InternalReconciliationJob(
    private val reconciliationService: InternalReconciliationService
) {
    /**
     * 매일 새벽 3시에 전날(T-1) 거래 대사와 과거 OPEN 결과 재검사를 수행한다.
     */
    @Scheduled(cron = "0 0 3 * * *")
    fun runDailyInternalReconciliation() {
        val yesterday = LocalDate.now().minusDays(1)

        // 전날 성공 거래 대사 (신규 누락 발견)
        reconciliationService.detectMismatches(yesterday)

        // 과거 OPEN 결과 재검사 (지연된 원장 자동 해결)
        reconciliationService.resolveOpenMismatches()
    }
}
