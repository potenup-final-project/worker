package com.pg.worker.settlement.scheduler

import com.pg.worker.settlement.application.service.ExternalTransactionFetchService
import com.pg.worker.settlement.application.service.SettlementReconciliationEngine
import com.pg.worker.global.logging.context.TraceScope
import com.gop.logging.contract.StructuredLogger
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDate

@Component
class ExternalReconciliationJob(
    private val fetchService: ExternalTransactionFetchService,
    private val reconciliationEngine: SettlementReconciliationEngine,
    private val log: StructuredLogger) {

    @Scheduled(cron = "0 30 3 * * *", scheduler = "settlementWorkerScheduler")
    fun runDailyExternalReconciliation() {
        val runTraceId = TraceScope.newRunTraceId("worker-external-recon")
        TraceScope.withTraceContext(traceId = runTraceId, messageId = "external-reconciliation-job") {
            val targetDate = LocalDate.now().minusDays(1)
            log.info("[외부대사-배치] 시작. 대상일={}", targetDate)

            try {
                fetchService.fetchAndSync(targetDate)
                reconciliationEngine.reconcile(targetDate)
                log.info("[외부대사-배치] 완료. 대상일={}", targetDate)
            } catch (e: Exception) {
                log.error("[외부대사-배치] 실패. 대상일={}", targetDate, e)
            }
        }
    }
}
