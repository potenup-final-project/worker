package com.pg.worker.settlement.scheduler

import com.pg.worker.settlement.application.service.SettlementRetryClaimService
import com.pg.worker.settlement.application.service.SettlementRetryProcessor
import com.pg.worker.global.logging.context.TraceScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import com.gop.logging.contract.StructuredLogger
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class SettlementRetryScheduler(
    private val claimService: SettlementRetryClaimService,
    private val retryProcessor: SettlementRetryProcessor,
    private val log: StructuredLogger) {
    private val maxConcurrentWorkers = 8

    /**
     * 재처리 스케줄러 (1분마다 실행)
     */
    @Scheduled(fixedDelay = 60000, scheduler = "settlementWorkerScheduler")
    fun runRetry() {
        val runTraceId = TraceScope.newRunTraceId("worker-settlement-retry")
        val runMessageId = "settlement-retry-run"

        TraceScope.withTraceContext(traceId = runTraceId, messageId = runMessageId) {
            log.info("[정산-재처리-스케줄러] 재처리 작업을 시작합니다.")

            val stuckIds = claimService.claimStuckTargets(batchSize = 50)
            val targetIds = claimService.claimRetryTargets(batchSize = 100)
            val allTargets = (stuckIds + targetIds).distinct()

            processTargetsConcurrently(allTargets, runTraceId)

            log.info(
                "[정산-재처리-스케줄러] 재처리 작업을 종료합니다. (복구된 Stuck: {}, 처리된 대상: {})",
                stuckIds.size,
                targetIds.size
            )
        }
    }

    private fun processTargetsConcurrently(rawIds: List<Long>, runTraceId: String) {
        if (rawIds.isEmpty()) return

        runBlocking {
            rawIds.chunked(maxConcurrentWorkers).forEach { chunk ->
                chunk.map { rawId ->
                    async(Dispatchers.IO) {
                        TraceScope.withOriginOrRunTrace(
                            originTraceId = null,
                            runTraceId = runTraceId,
                            messageId = "settlement-retry-run",
                            eventId = rawId.toString()
                        ) {
                            retryProcessor.processRetry(rawId)
                        }
                    }
                }.awaitAll()
            }
        }
    }
}
