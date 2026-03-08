package com.pg.worker.settlement.application.service

import com.pg.worker.settlement.application.repository.SettlementRawDataRepository
import com.pg.worker.settlement.domain.SettlementRetryPolicy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Component
class SettlementStatusUpdater(
    private val rawRepository: SettlementRawDataRepository
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun updateToPending(rawId: Long, reason: String) {
        rawRepository.findById(rawId)?.apply {
            markPendingDependency(reason, LocalDateTime.now().plusMinutes(SettlementRetryPolicy.PENDING_DELAY_MINUTES))
            rawRepository.save(this)
        } ?: log.warn("[Settlement] [Updater] PENDING 상태 전이 실패. 데이터를 찾을 수 없음. rawId={}, reason={}", rawId, reason)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun updateToFailedRetryable(rawId: Long, reason: String) {
        rawRepository.findById(rawId)?.apply {
            markFailedRetryable(reason, LocalDateTime.now().plusMinutes(SettlementRetryPolicy.RETRY_DELAY_MINUTES))
            rawRepository.save(this)
        } ?: log.warn("[Settlement] [Updater] RETRYABLE 상태 전이 실패. 데이터를 찾을 수 없음. rawId={}, reason={}", rawId, reason)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun updateToFailedNonRetryable(rawId: Long, reason: String) {
        rawRepository.findById(rawId)?.apply {
            markFailedNonRetryable(reason)
            rawRepository.save(this)
        } ?: log.warn("[Settlement] [Updater] NON_RETRYABLE 상태 전이 실패. 데이터를 찾을 수 없음. rawId={}, reason={}", rawId, reason)
    }
}
