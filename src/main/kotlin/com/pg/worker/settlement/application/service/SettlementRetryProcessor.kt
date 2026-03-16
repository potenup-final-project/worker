package com.pg.worker.settlement.application.service

import com.gop.logging.contract.LogPrefix
import com.gop.logging.contract.LogResult
import com.gop.logging.contract.LogSuffix
import com.gop.logging.contract.LogType
import com.gop.logging.contract.ProcessResult
import com.gop.logging.contract.StepPrefix
import com.gop.logging.contract.StructuredLogger
import com.pg.worker.settlement.domain.exception.NonRetryableException
import com.pg.worker.settlement.domain.exception.PendingDependencyException
import com.pg.worker.settlement.domain.exception.RetryableException
import com.pg.worker.settlement.domain.exception.SettlementException
import org.springframework.stereotype.Service

@Service
@LogPrefix(StepPrefix.SETTLEMENT_RETRY)
class SettlementRetryProcessor(
    private val ledgerProcessor: SettlementLedgerProcessor,
    private val statusUpdater: SettlementStatusUpdater,
    private val structuredLogger: StructuredLogger,
) {

    @LogSuffix("processRetry")
    fun processRetry(rawId: Long) {
        structuredLogger.info(
            logType = LogType.FLOW,
            result = LogResult.START,
            payload = mapOf("rawId" to rawId)
        )

        val startMs = System.currentTimeMillis()
        try {
            ledgerProcessor.process(rawId)

            structuredLogger.info(
                logType = LogType.FLOW,
                result = LogResult.END,
                payload = mapOf("processResult" to ProcessResult.SUCCESS.name, "rawId" to rawId, "durationMs" to (System.currentTimeMillis() - startMs))
            )
        } catch (e: SettlementException) {
            when (e) {
                is PendingDependencyException -> {
                    structuredLogger.info(
                        logType = LogType.FLOW,
                        result = LogResult.END,
                        payload = mapOf("processResult" to ProcessResult.RETRY.name, "rawId" to rawId, "reason" to "pending_dependency", "durationMs" to (System.currentTimeMillis() - startMs)),
                        error = e
                    )
                    statusUpdater.updateToPending(rawId, e.message ?: "Dependency missing")
                }
                is NonRetryableException -> {
                    structuredLogger.error(
                        logType = LogType.FLOW,
                        result = LogResult.END,
                        payload = mapOf("processResult" to ProcessResult.FAIL.name, "rawId" to rawId, "reason" to "non_retryable", "durationMs" to (System.currentTimeMillis() - startMs)),
                        error = e
                    )
                    statusUpdater.updateToFailedNonRetryable(rawId, e.message ?: "Non-retryable error")
                }
                is RetryableException -> {
                    structuredLogger.warn(
                        logType = LogType.FLOW,
                        result = LogResult.END,
                        payload = mapOf("processResult" to ProcessResult.RETRY.name, "rawId" to rawId, "reason" to "retryable_error", "durationMs" to (System.currentTimeMillis() - startMs)),
                        error = e
                    )
                    statusUpdater.updateToFailedRetryable(rawId, e.message ?: "Transient error")
                }
            }
        } catch (e: Exception) {
            structuredLogger.error(
                logType = LogType.FLOW,
                result = LogResult.END,
                payload = mapOf("processResult" to ProcessResult.RETRY.name, "rawId" to rawId, "reason" to "unexpected_error", "durationMs" to (System.currentTimeMillis() - startMs)),
                error = e
            )
            statusUpdater.updateToFailedRetryable(rawId, "Unexpected System Error: ${e.message}")
        }
    }
}
