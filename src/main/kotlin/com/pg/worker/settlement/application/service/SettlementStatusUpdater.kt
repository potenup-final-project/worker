package com.pg.worker.settlement.application.service

import com.gop.logging.contract.LogPrefix
import com.gop.logging.contract.LogResult
import com.gop.logging.contract.LogSuffix
import com.gop.logging.contract.LogType
import com.gop.logging.contract.StepPrefix
import com.gop.logging.contract.StructuredLogger
import com.pg.worker.global.logging.WorkerLogPayloadKeys
import com.pg.worker.settlement.application.repository.SettlementRawDataRepository
import com.pg.worker.settlement.consumer.SettlementDispatchDlqPublisher
import com.pg.worker.settlement.domain.RawDataStatus
import com.pg.worker.settlement.domain.SettlementRawData
import com.pg.worker.settlement.domain.SettlementRetryPolicy
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Component
@LogPrefix(StepPrefix.SETTLEMENT_LEDGER)
class SettlementStatusUpdater(
    private val rawRepository: SettlementRawDataRepository,
    private val dlqPublisher: SettlementDispatchDlqPublisher,
    private val structuredLogger: StructuredLogger,
    @Value("\${settlement.sqs.queue-url}") private val settlementQueueUrl: String,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @LogSuffix("updateToPending")
    fun updateToPending(rawId: Long, reason: String): SettlementRawData? {
        val raw = rawRepository.findById(rawId) ?: run {
            structuredLogger.warn(
                logType = LogType.FLOW,
                result = LogResult.SKIP,
                payload = mapOf(
                    WorkerLogPayloadKeys.PHASE to "status_transition_not_found",
                    WorkerLogPayloadKeys.TARGET_STATUS to "PENDING_DEPENDENCY",
                    WorkerLogPayloadKeys.RAW_ID to rawId,
                    WorkerLogPayloadKeys.REASON to reason
                )
            )
            return null
        }

        if (raw.retryCount >= SettlementRetryPolicy.MAX_RETRY_COUNT) {
            raw.markFailedNonRetryable("Pending dependency timeout: $reason")
            structuredLogger.warn(
                logType = LogType.FLOW,
                result = LogResult.FAIL,
                payload = mapOf(
                    WorkerLogPayloadKeys.PHASE to "pending_exhausted",
                    WorkerLogPayloadKeys.RAW_ID to rawId,
                    WorkerLogPayloadKeys.RETRY_COUNT to raw.retryCount,
                    WorkerLogPayloadKeys.REASON to reason
                )
            )
        } else {
            val delayMinutes = SettlementRetryPolicy.pendingNextRetryDelayMinutes(raw.retryCount)
            raw.markPendingDependency(reason, LocalDateTime.now().plusMinutes(delayMinutes))
            structuredLogger.info(
                logType = LogType.FLOW,
                result = LogResult.RETRY,
                payload = mapOf(
                    WorkerLogPayloadKeys.PHASE to "pending_scheduled",
                    WorkerLogPayloadKeys.RAW_ID to rawId,
                    WorkerLogPayloadKeys.RETRY_COUNT to raw.retryCount,
                    WorkerLogPayloadKeys.NEXT_RETRY_AT to raw.nextRetryAt,
                    WorkerLogPayloadKeys.REASON to reason
                )
            )
        }

        return rawRepository.save(raw).also {
            publishIfRetryExhausted(it, reason)
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @LogSuffix("updateToRetryable")
    fun updateToFailedRetryable(rawId: Long, reason: String): SettlementRawData? {
        val raw = rawRepository.findById(rawId) ?: run {
            structuredLogger.warn(
                logType = LogType.FLOW,
                result = LogResult.SKIP,
                payload = mapOf(
                    WorkerLogPayloadKeys.PHASE to "status_transition_not_found",
                    WorkerLogPayloadKeys.TARGET_STATUS to "FAILED_RETRYABLE",
                    WorkerLogPayloadKeys.RAW_ID to rawId,
                    WorkerLogPayloadKeys.REASON to reason
                )
            )
            return null
        }

        if (raw.retryCount >= SettlementRetryPolicy.MAX_RETRY_COUNT) {
            raw.markFailedNonRetryable("Max retry exhausted: $reason")
            structuredLogger.warn(
                logType = LogType.FLOW,
                result = LogResult.FAIL,
                payload = mapOf(
                    WorkerLogPayloadKeys.PHASE to "retry_exhausted",
                    WorkerLogPayloadKeys.RAW_ID to rawId,
                    WorkerLogPayloadKeys.RETRY_COUNT to raw.retryCount,
                    WorkerLogPayloadKeys.REASON to reason
                )
            )
        } else {
            val delayMinutes = SettlementRetryPolicy.retryableNextRetryDelayMinutes(raw.retryCount)
            raw.markFailedRetryable(reason, LocalDateTime.now().plusMinutes(delayMinutes))
            structuredLogger.warn(
                logType = LogType.FLOW,
                result = LogResult.RETRY,
                payload = mapOf(
                    WorkerLogPayloadKeys.PHASE to "retry_scheduled",
                    WorkerLogPayloadKeys.RAW_ID to rawId,
                    WorkerLogPayloadKeys.RETRY_COUNT to raw.retryCount,
                    WorkerLogPayloadKeys.NEXT_RETRY_AT to raw.nextRetryAt,
                    WorkerLogPayloadKeys.REASON to reason
                )
            )
        }

        return rawRepository.save(raw).also {
            publishIfRetryExhausted(it, reason)
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @LogSuffix("updateToNonRetryable")
    fun updateToFailedNonRetryable(rawId: Long, reason: String): SettlementRawData? {
        val raw = rawRepository.findById(rawId) ?: run {
            structuredLogger.warn(
                logType = LogType.FLOW,
                result = LogResult.SKIP,
                payload = mapOf(
                    WorkerLogPayloadKeys.PHASE to "status_transition_not_found",
                    WorkerLogPayloadKeys.TARGET_STATUS to "FAILED_NON_RETRYABLE",
                    WorkerLogPayloadKeys.RAW_ID to rawId,
                    WorkerLogPayloadKeys.REASON to reason
                )
            )
            return null
        }

        raw.markFailedNonRetryable(reason)
        structuredLogger.warn(
            logType = LogType.FLOW,
            result = LogResult.FAIL,
            payload = mapOf(
                WorkerLogPayloadKeys.PHASE to "non_retryable_marked",
                WorkerLogPayloadKeys.RAW_ID to rawId,
                WorkerLogPayloadKeys.RETRY_COUNT to raw.retryCount,
                WorkerLogPayloadKeys.REASON to reason
            )
        )
        return rawRepository.save(raw)
    }

    private fun publishIfRetryExhausted(raw: SettlementRawData, reason: String) {
        if (raw.status != RawDataStatus.FAILED_NON_RETRYABLE) {
            return
        }

        val finalReason = raw.failureReason ?: reason
        val dlqSent = dlqPublisher.publishRetryExhausted(
            originalQueueUrl = settlementQueueUrl,
            eventId = raw.eventId,
            merchantId = raw.merchantId,
            rawId = raw.id,
            retryCount = raw.retryCount,
            failureReason = finalReason,
        )

        if (!dlqSent) {
            structuredLogger.error(
                logType = LogType.INTEGRATION,
                result = LogResult.FAIL,
                payload = mapOf(
                    WorkerLogPayloadKeys.PHASE to "retry_exhausted_dlq_publish",
                    WorkerLogPayloadKeys.RAW_ID to raw.id,
                    WorkerLogPayloadKeys.EVENT_ID to raw.eventId,
                    WorkerLogPayloadKeys.RETRY_COUNT to raw.retryCount,
                    WorkerLogPayloadKeys.REASON to finalReason
                )
            )
            return
        }

        structuredLogger.warn(
            logType = LogType.INTEGRATION,
            result = LogResult.SUCCESS,
            payload = mapOf(
                WorkerLogPayloadKeys.PHASE to "retry_exhausted_dlq_publish",
                WorkerLogPayloadKeys.RAW_ID to raw.id,
                WorkerLogPayloadKeys.EVENT_ID to raw.eventId,
                WorkerLogPayloadKeys.RETRY_COUNT to raw.retryCount,
                WorkerLogPayloadKeys.REASON to finalReason
            )
        )
    }
}
