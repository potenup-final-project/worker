package com.pg.worker.settlement.scheduler

import com.pg.worker.settlement.application.service.InternalReconciliationService
import com.pg.worker.global.logging.context.TraceScope
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
    @Scheduled(cron = "0 0 3 * * *", scheduler = "settlementWorkerScheduler")
    fun runDailyInternalReconciliation() {
        val runTraceId = TraceScope.newRunTraceId("worker-internal-recon")
        TraceScope.withTraceContext(traceId = runTraceId, messageId = "internal-reconciliation-job") {
            val yesterday = LocalDate.now().minusDays(1)
            reconciliationService.detectMismatches(yesterday)
            reconciliationService.resolveOpenMismatches()
        }
    }
}
