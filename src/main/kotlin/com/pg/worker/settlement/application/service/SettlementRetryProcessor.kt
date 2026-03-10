package com.pg.worker.settlement.application.service

import com.pg.worker.settlement.domain.exception.NonRetryableException
import com.pg.worker.settlement.domain.exception.PendingDependencyException
import com.pg.worker.settlement.domain.exception.RetryableException
import com.pg.worker.settlement.domain.exception.SettlementException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class SettlementRetryProcessor(
    private val ledgerProcessor: SettlementLedgerProcessor,
    private val statusUpdater: SettlementStatusUpdater
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 개별 Raw 데이터 재처리 (Individual Transaction per Row)
     */
    fun processRetry(rawId: Long) {
        try {
            log.info("[정산-재처리] 재처리 시작. rawId={}", rawId)
            
            ledgerProcessor.process(rawId)
            
            log.info("[정산-재처리] 재처리 완료. rawId={}", rawId)
            
        } catch (e: SettlementException) {
            when (e) {
                is PendingDependencyException -> {
                    log.info("[정산-재처리] 의존성 미충족 대기 (재시도). rawId={}, reason={}", rawId, e.message)
                    statusUpdater.updateToPending(rawId, e.message ?: "Dependency missing")
                }
                is NonRetryableException -> {
                    log.error("[정산-재처리] 비즈니스 오류 (영구 실패). rawId={}, reason={}", rawId, e.message)
                    statusUpdater.updateToFailedNonRetryable(rawId, e.message ?: "Non-retryable error")
                }
                is RetryableException -> {
                    log.warn("[정산-재처리] 기술적 일시 오류 (재시도 예약). rawId={}, reason={}", rawId, e.message)
                    statusUpdater.updateToFailedRetryable(rawId, e.message ?: "Transient error")
                }
            }
        } catch (e: Exception) {
            log.error("[정산-재처리] 예상치 못한 시스템 오류 (재시도 예약). rawId={}, error={}", rawId, e.message, e)
            statusUpdater.updateToFailedRetryable(rawId, "Unexpected System Error: ${e.message}")
        }
    }
}
