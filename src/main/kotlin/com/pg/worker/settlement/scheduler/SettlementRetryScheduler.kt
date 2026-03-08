package com.pg.worker.settlement.scheduler

import com.pg.worker.settlement.application.service.SettlementRetryClaimService
import com.pg.worker.settlement.application.service.SettlementRetryProcessor
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class SettlementRetryScheduler(
    private val claimService: SettlementRetryClaimService,
    private val retryProcessor: SettlementRetryProcessor
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 재처리 스케줄러 (1분마다 실행)
     */
    @Scheduled(fixedDelay = 60000)
    fun runRetry() {
        log.info("[정산-재처리-스케줄러] 재처리 작업을 시작합니다.")
        
        // 1. Stuck Processing 복구 (오래된 PROCESSING 상태를 다시 가져옴)
        val stuckIds = claimService.claimStuckTargets(batchSize = 50)
        stuckIds.forEach { rawId ->
            retryProcessor.processRetry(rawId)
        }

        // 2. 일반 재처리 대상 처리 (PENDING, RETRYABLE)
        val targetIds = claimService.claimRetryTargets(batchSize = 100)
        targetIds.forEach { rawId ->
            retryProcessor.processRetry(rawId)
        }

        log.info("[정산-재처리-스케줄러] 재처리 작업을 종료합니다. (복구된 Stuck: {}, 처리된 대상: {})", 
            stuckIds.size, targetIds.size)
    }
}
