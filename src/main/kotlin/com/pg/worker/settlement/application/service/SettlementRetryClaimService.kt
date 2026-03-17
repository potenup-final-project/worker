package com.pg.worker.settlement.application.service

import com.pg.worker.settlement.application.repository.SettlementRawDataRepository
import com.pg.worker.settlement.domain.RawDataStatus
import com.pg.worker.settlement.domain.SettlementRetryPolicy
import com.gop.logging.contract.StructuredLogger
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class SettlementRetryClaimService(
    private val rawRepository: SettlementRawDataRepository,
    private val log: StructuredLogger) {

    /**
     * 재처리 대상 선점 (Short Transaction)
     * SKIP LOCKED를 사용하여 여러 인스턴스가 중복해서 가져가지 않도록 한다.
     */
    @Transactional
    fun claimRetryTargets(batchSize: Int): List<Long> {
        val now = LocalDateTime.now()
        val statuses = listOf(RawDataStatus.PENDING_DEPENDENCY, RawDataStatus.FAILED_RETRYABLE)
        
        val targets = rawRepository.findRetryableDataForClaim(statuses, now, batchSize)
        
        return targets.map { raw ->
            raw.claim()
            rawRepository.save(raw)
            raw.id
        }.also {
            if (it.isNotEmpty()) {
                log.info("[Settlement-Retry] {} 건의 재처리 대상을 선점했습니다.", it.size)
            }
        }
    }

    /**
     * 장기 PROCESSING 상태(Stuck) 데이터 선점 및 복구 준비
     */
    @Transactional
    fun claimStuckTargets(batchSize: Int): List<Long> {
        val threshold = LocalDateTime.now().minusMinutes(SettlementRetryPolicy.STUCK_THRESHOLD_MINUTES)
        
        val targets = rawRepository.findStuckProcessingDataForClaim(threshold, batchSize)
        
        return targets.map { raw ->
            log.warn("[Settlement-Retry] Stuck 데이터 발견 및 재선점. rawId={}, lastClaimedAt={}", raw.id, raw.claimedAt)
            raw.claim()
            rawRepository.save(raw)
            raw.id
        }.also {
            if (it.isNotEmpty()) {
                log.info("[Settlement-Retry] {} 건의 Stuck 데이터를 선점했습니다.", it.size)
            }
        }
    }
}
