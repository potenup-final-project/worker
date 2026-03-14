package com.pg.worker.settlement.application.service

import com.gop.logging.contract.LogPrefix
import com.gop.logging.contract.LogResult
import com.gop.logging.contract.LogSuffix
import com.gop.logging.contract.LogType
import com.gop.logging.contract.StepPrefix
import com.gop.logging.contract.StructuredLogger
import com.pg.worker.global.logging.WorkerLogPayloadKeys
import com.pg.worker.settlement.application.usecase.command.RecordSettlementCommandUseCase
import com.pg.worker.settlement.application.usecase.command.dto.RecordSettlementCommand
import com.pg.worker.settlement.application.usecase.command.dto.RecordSettlementResult
import com.pg.worker.settlement.domain.RawDataStatus
import com.pg.worker.settlement.domain.SettlementRawData
import com.pg.worker.settlement.domain.exception.NonRetryableException
import com.pg.worker.settlement.domain.exception.PendingDependencyException
import com.pg.worker.settlement.domain.exception.RetryableException
import org.springframework.stereotype.Service

@Service
@LogPrefix(StepPrefix.SETTLEMENT_LEDGER)
class RecordSettlementService(
    private val rawDataWriter: SettlementRawDataWriter,
    private val ledgerProcessor: SettlementLedgerProcessor,
    private val statusUpdater: SettlementStatusUpdater,
    private val structuredLogger: StructuredLogger
) : RecordSettlementCommandUseCase {

    @LogSuffix("record")
    override fun record(command: RecordSettlementCommand): RecordSettlementResult {
        val raw = rawDataWriter.write(command) ?: run {
            structuredLogger.info(
                logType = LogType.FLOW,
                result = LogResult.SKIP,
                payload = mapOf(
                    WorkerLogPayloadKeys.REASON to "already_processed_raw_event",
                    WorkerLogPayloadKeys.EVENT_ID to command.eventId
                )
            )
            return RecordSettlementResult.SkippedAlreadyProcessed
        }

        try {
            ledgerProcessor.process(raw.id)
            structuredLogger.info(
                logType = LogType.FLOW,
                result = LogResult.SUCCESS,
                payload = mapOf(
                    WorkerLogPayloadKeys.PHASE to "ledger_processed",
                    WorkerLogPayloadKeys.EVENT_ID to command.eventId,
                    WorkerLogPayloadKeys.RAW_ID to raw.id
                )
            )
            return RecordSettlementResult.Success
        } catch (e: Exception) {
            return handleProcessingFailure(raw.id, command.eventId, e)
        }
    }

    private fun handleProcessingFailure(rawId: Long, eventId: String, e: Exception): RecordSettlementResult {
        when (e) {
                is PendingDependencyException -> {
                    structuredLogger.info(
                        logType = LogType.FLOW,
                        result = LogResult.RETRY,
                        payload = mapOf(
                            WorkerLogPayloadKeys.PHASE to "pending_dependency",
                            WorkerLogPayloadKeys.EVENT_ID to eventId,
                            WorkerLogPayloadKeys.REASON to e.message
                        )
                    )
                    val updated = updateStatusSafely(rawId, eventId, "PENDING") {
                        statusUpdater.updateToPending(rawId, e.message ?: "Dependency missing")
                    }
                    return toResult(updated, e.message ?: "Dependency missing")
                }
                is NonRetryableException -> {
                    structuredLogger.warn(
                        logType = LogType.FLOW,
                        result = LogResult.FAIL,
                        payload = mapOf(
                            WorkerLogPayloadKeys.PHASE to "non_retryable_failure",
                            WorkerLogPayloadKeys.EVENT_ID to eventId,
                            WorkerLogPayloadKeys.REASON to e.message
                        )
                    )
                    updateStatusSafely(rawId, eventId, "NON_RETRYABLE") {
                        statusUpdater.updateToFailedNonRetryable(rawId, e.message ?: "Non-retryable error")
                    }
                    return RecordSettlementResult.NonRetryableFailed(e.message ?: "Non-retryable error")
                }
                is RetryableException -> {
                    structuredLogger.warn(
                        logType = LogType.FLOW,
                        result = LogResult.RETRY,
                        payload = mapOf(
                            WorkerLogPayloadKeys.PHASE to "retryable_failure",
                            WorkerLogPayloadKeys.EVENT_ID to eventId,
                            WorkerLogPayloadKeys.REASON to e.message
                        )
                    )
                    val updated = updateStatusSafely(rawId, eventId, "RETRYABLE") {
                        statusUpdater.updateToFailedRetryable(rawId, e.message ?: "Transient error")
                    }
                    return toResult(updated, e.message ?: "Transient error")
                }
            else -> {
                structuredLogger.error(
                    logType = LogType.TECHNICAL,
                    result = LogResult.FAIL,
                    payload = mapOf(
                        WorkerLogPayloadKeys.PHASE to "unexpected_system_error",
                        WorkerLogPayloadKeys.EVENT_ID to eventId,
                        WorkerLogPayloadKeys.RAW_ID to rawId,
                        WorkerLogPayloadKeys.ERROR to e.message
                    ),
                    error = e
                )
                updateStatusSafely(rawId, eventId, "NON_RETRYABLE") {
                    statusUpdater.updateToFailedNonRetryable(rawId, "Unexpected System Error: ${e.message}")
                }
                return RecordSettlementResult.NonRetryableFailed("Unexpected System Error: ${e.message}")
            }
        }
    }

    private fun toResult(updated: SettlementRawData?, reason: String): RecordSettlementResult {
        if (updated == null) {
            return RecordSettlementResult.NonRetryableFailed("STATUS_UPDATE_FAILED: $reason")
        }

        return when (updated.status) {
            RawDataStatus.FAILED_NON_RETRYABLE -> RecordSettlementResult.NonRetryableFailed(updated.failureReason ?: reason)
            RawDataStatus.PENDING_DEPENDENCY,
            RawDataStatus.FAILED_RETRYABLE,
                -> RecordSettlementResult.RetryScheduled(
                    reason = updated.failureReason ?: reason,
                    retryCount = updated.retryCount,
                    nextRetryAt = updated.nextRetryAt
                )

            else -> RecordSettlementResult.RetryScheduled(
                reason = updated.failureReason ?: reason,
                retryCount = updated.retryCount,
                nextRetryAt = updated.nextRetryAt
            )
        }
    }

    private inline fun updateStatusSafely(
        rawId: Long,
        eventId: String,
        statusLabel: String,
        update: () -> SettlementRawData?
    ): SettlementRawData? {
        try {
            return update()
        } catch (e: Exception) {
            structuredLogger.error(
                logType = LogType.TECHNICAL,
                result = LogResult.FAIL,
                payload = mapOf(
                    WorkerLogPayloadKeys.PHASE to "status_update_failure",
                    WorkerLogPayloadKeys.STATUS_LABEL to statusLabel,
                    WorkerLogPayloadKeys.EVENT_ID to eventId,
                    WorkerLogPayloadKeys.RAW_ID to rawId
                ),
                error = e
            )
            return null
        }
    }
}
