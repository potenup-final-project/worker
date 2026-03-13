package com.pg.worker.settlement.application.service

import com.pg.worker.settlement.application.usecase.command.RecordSettlementCommandUseCase
import com.pg.worker.settlement.application.usecase.command.dto.RecordSettlementCommand
import com.pg.worker.settlement.domain.exception.NonRetryableException
import com.pg.worker.settlement.domain.exception.PendingDependencyException
import com.pg.worker.settlement.domain.exception.RetryableException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class RecordSettlementService(
    private val rawDataWriter: SettlementRawDataWriter,
    private val ledgerProcessor: SettlementLedgerProcessor,
    private val statusUpdater: SettlementStatusUpdater
) : RecordSettlementCommandUseCase {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun record(command: RecordSettlementCommand) {
        val raw = rawDataWriter.write(command) ?: run {
            log.info("[Settlement] 이미 처리된 Raw 이벤트이므로 종료. eventId={}", command.eventId)
            return
        }

        try {
            ledgerProcessor.process(raw.id)
            log.info("[Settlement] 정산 처리 완료. eventId={}", command.eventId)
        } catch (e: Exception) {
            handleProcessingFailure(raw.id, command.eventId, e)
        }
    }

    private fun handleProcessingFailure(rawId: Long, eventId: String, e: Exception) {
        when (e) {
                is PendingDependencyException -> {
                    log.info("[Settlement] 의존성 미충족 대기. eventId={}, reason={}", eventId, e.message)
                    updateStatusSafely(rawId, eventId, "PENDING") {
                        statusUpdater.updateToPending(rawId, e.message ?: "Dependency missing")
                    }
                }
                is NonRetryableException -> {
                    log.warn("[Settlement] 비즈니스/정합성 오류 (영구 실패). eventId={}, reason={}", eventId, e.message)
                    updateStatusSafely(rawId, eventId, "NON_RETRYABLE") {
                        statusUpdater.updateToFailedNonRetryable(rawId, e.message ?: "Non-retryable error")
                    }
                }
                is RetryableException -> {
                    log.warn("[Settlement] 기술적 일시 오류 (재시도 대상). eventId={}, reason={}", eventId, e.message)
                    updateStatusSafely(rawId, eventId, "RETRYABLE") {
                        statusUpdater.updateToFailedRetryable(rawId, e.message ?: "Transient error")
                    }
                }
            else -> {
                log.error(
                    "[Settlement] [CRITICAL] 예상치 못한 시스템 오류 발생. 즉시 확인 필요. eventId={}, error={}",
                    eventId, e.message, e
                )
                updateStatusSafely(rawId, eventId, "NON_RETRYABLE") {
                    statusUpdater.updateToFailedNonRetryable(rawId, "Unexpected System Error: ${e.message}")
                }
            }
        }
    }

    private inline fun updateStatusSafely(rawId: Long, eventId: String, statusLabel: String, update: () -> Unit) {
        try {
            update()
        } catch (e: Exception) {
            log.error(
                "[Settlement] 상태 업데이트 실패({}). 내부 재처리 체계 이탈 가능성. DLQ 확인 필요. eventId={}, rawId={}",
                statusLabel, eventId, rawId, e
            )
        }
    }
}
