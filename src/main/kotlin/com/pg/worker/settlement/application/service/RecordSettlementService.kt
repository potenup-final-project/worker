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
        val rawId = raw.id

        try {
            ledgerProcessor.process(rawId)
            log.info("[Settlement] 정산 처리 완료. eventId={}", command.eventId)
        } catch (e: PendingDependencyException) {
            log.info("[Settlement] 의존성 미충족 대기. eventId={}, reason={}", command.eventId, e.message)
            safelyUpdatePending(rawId, e, command.eventId)
        } catch (e: NonRetryableException) {
            log.error("[Settlement] 비즈니스/정합성 오류 (영구 실패). eventId={}, reason={}", command.eventId, e.message)
            safelyUpdateNonRetryable(rawId, e, command.eventId)
        } catch (e: RetryableException) {
            log.warn("[Settlement] 기술적 일시 오류 (재시도 대상). eventId={}, reason={}", command.eventId, e.message)
            safelyUpdateRetryable(rawId, e, command.eventId)
        } catch (e: Exception) {
            /**
             * [운영 정책: 예상치 못한 예외 처리]
             * 로직 오류(NPE, ClassCast 등)나 코딩 버그는 재시도해도 성공 가능성이 낮으므로
             * 앱 내부 상태를 NON_RETRYABLE로 기록하고 운영 개입을 유도한다.
             *
             * 단, 이 상태 기록 자체가 실패하면 SQS 재시도를 위해 예외를 다시 던진다.
             */
            log.error(
                "[Settlement] [CRITICAL] 예상치 못한 시스템 오류 발생. 즉시 확인 필요. eventId={}, error={}",
                command.eventId,
                e.message,
                e
            )
            safelyUpdateNonRetryable(
                rawId = rawId,
                cause = e,
                eventId = command.eventId,
                reason = "Unexpected System Error: ${e.message}"
            )
        }
    }

    private fun safelyUpdatePending(rawId: Long, cause: Exception, eventId: String) {
        try {
            statusUpdater.updateToPending(rawId, cause.message ?: "Dependency missing")
        } catch (updateEx: Exception) {
            log.error(
                "[Settlement] 상태 업데이트 실패(PENDING). SQS 재시도를 위해 예외 재전파. eventId={}, rawId={}",
                eventId,
                rawId,
                updateEx
            )
            throw updateEx
        }
    }

    private fun safelyUpdateRetryable(rawId: Long, cause: Exception, eventId: String) {
        try {
            statusUpdater.updateToFailedRetryable(rawId, cause.message ?: "Transient error")
        } catch (updateEx: Exception) {
            log.error(
                "[Settlement] 상태 업데이트 실패(RETRYABLE). SQS 재시도를 위해 예외 재전파. eventId={}, rawId={}",
                eventId,
                rawId,
                updateEx
            )
            throw updateEx
        }
    }

    private fun safelyUpdateNonRetryable(
        rawId: Long,
        cause: Exception,
        eventId: String,
        reason: String = cause.message ?: "Non-retryable error"
    ) {
        try {
            statusUpdater.updateToFailedNonRetryable(rawId, reason)
        } catch (updateEx: Exception) {
            log.error(
                "[Settlement] 상태 업데이트 실패(NON_RETRYABLE). SQS 재시도를 위해 예외 재전파. eventId={}, rawId={}",
                eventId,
                rawId,
                updateEx
            )
            throw updateEx
        }
    }
}
