package com.pg.worker.settlement.application.service

import com.pg.worker.settlement.application.repository.SettlementLedgerRepository
import com.pg.worker.settlement.application.repository.SettlementPolicyRepository
import com.pg.worker.settlement.application.repository.SettlementRawDataRepository
import com.pg.worker.settlement.domain.SettlementLedger
import com.pg.worker.settlement.domain.SettlementPolicy
import com.pg.worker.settlement.domain.SettlementRawData
import com.pg.worker.settlement.domain.TransactionType
import com.pg.worker.settlement.domain.exception.NonRetryableException
import com.pg.worker.settlement.domain.exception.PendingDependencyException
import com.pg.worker.settlement.domain.exception.RetryableException
import com.gop.logging.contract.LogPrefix
import com.gop.logging.contract.LogResult
import com.gop.logging.contract.LogSuffix
import com.gop.logging.contract.LogType
import com.gop.logging.contract.ProcessResult
import com.gop.logging.contract.StepPrefix
import com.gop.logging.contract.StructuredLogger
import org.springframework.dao.ConcurrencyFailureException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.DuplicateKeyException
import org.springframework.dao.TransientDataAccessException
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode

@Component
@LogPrefix(StepPrefix.SETTLEMENT_LEDGER)
class SettlementLedgerProcessor(
    private val rawRepository: SettlementRawDataRepository,
    private val ledgerRepository: SettlementLedgerRepository,
    private val policyRepository: SettlementPolicyRepository,
    private val structuredLogger: StructuredLogger,
) {

    // 카드사 원가 수수료율 고정 (테스트용 2.1%)
    private val HOST_FEE_RATE = BigDecimal("0.021")

    @LogSuffix("process")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun process(rawId: Long) {
        val raw = loadRaw(rawId) ?: return

        if (isAlreadyProcessed(raw)) {
            markProcessed(raw)
            return
        }

        validateTransactionType(raw)

        try {
            when (raw.transactionType) {
                TransactionType.APPROVE -> handleApprove(raw)
                TransactionType.CANCEL -> handleCancel(raw)
                else -> throw NonRetryableException("지원하지 않는 거래 타입: ${raw.transactionType}")
            }
        } catch (e: DataIntegrityViolationException) {
            handleDataIntegrityViolation(raw, e)
        } catch (e: ObjectOptimisticLockingFailureException) {
            throw RetryableException("낙관적 락 충돌 (동시 수정 감지). eventId: ${raw.eventId}", e)
        } catch (e: ConcurrencyFailureException) {
            throw RetryableException("동시성 충돌(Deadlock 등)로 인한 재시도 가능. eventId: ${raw.eventId}", e)
        } catch (e: TransientDataAccessException) {
            throw RetryableException("DB 기술적 일시 장애로 인한 재시도 가능. eventId: ${raw.eventId}", e)
        }

        markProcessed(raw)
    }

    private fun loadRaw(rawId: Long): SettlementRawData? {
        return rawRepository.findById(rawId) ?: run {
            structuredLogger.warn(
                logType = LogType.FLOW,
                result = LogResult.SKIP,
                payload = mapOf("reason" to "raw_data_not_found", "rawId" to rawId)
            )
            null
        }
    }

    private fun isAlreadyProcessed(raw: SettlementRawData): Boolean {
        val exists = ledgerRepository.findByRawEventId(raw.eventId) != null
        if (exists) {
            structuredLogger.info(
                logType = LogType.FLOW,
                result = LogResult.SKIP,
                payload = mapOf("reason" to "already_processed", "eventId" to raw.eventId)
            )
        }
        return exists
    }

    private fun markProcessed(raw: SettlementRawData) {
        raw.markProcessed()
        rawRepository.save(raw)
    }

    private fun validateTransactionType(raw: SettlementRawData) {
        if (raw.transactionType == TransactionType.UNKNOWN) {
            throw NonRetryableException("잘못된 거래 타입으로 처리가 불가능합니다. eventId: ${raw.eventId}")
        }
    }

    private fun handleApprove(raw: SettlementRawData) {
        val policy = loadPolicyOrThrow(raw.merchantId)
        val ledger = buildApproveLedger(raw, policy)
        ledgerRepository.save(ledger)
    }

    private fun loadPolicyOrThrow(merchantId: Long): SettlementPolicy {
        return policyRepository.findByMerchantId(merchantId)
            ?: throw NonRetryableException("가맹점 정산 정책을 찾을 수 없습니다: $merchantId")
    }

    private fun buildApproveLedger(raw: SettlementRawData, policy: SettlementPolicy): SettlementLedger {
        val hostFee = calculateFee(raw.amount, HOST_FEE_RATE)
        val totalFee = calculateFee(raw.amount, policy.feeRate)
        val serviceFee = totalFee - hostFee // PG 마진 수수료
        
        val settlementBaseDate = raw.eventOccurredAt.plusDays(policy.settlementCycleDays.toLong()).toLocalDate()

        return SettlementLedger.create(
            raw = raw,
            originalPaymentTxId = null,
            hostFee = hostFee,
            serviceFee = serviceFee,
            settlementBaseDate = settlementBaseDate,
            policy = policy
        )
    }

    private fun handleCancel(raw: SettlementRawData) {
        val originalApproveRaw = loadOriginalApproveRawOrThrow(raw.paymentKey)
        val originalLedger = loadOriginalLedgerOrThrow(originalApproveRaw)

        val alreadyCancelledAmount = sumAlreadyCancelledAmount(originalApproveRaw.transactionId)
        validateCancelAmountNotExceeded(raw, originalLedger.amount, alreadyCancelledAmount)

        val ledger = buildCancelLedger(raw, originalApproveRaw, originalLedger)
        ledgerRepository.save(ledger)
    }

    private fun loadOriginalApproveRawOrThrow(paymentKey: String): SettlementRawData {
        return rawRepository.findByPaymentKeyAndTransactionType(paymentKey, TransactionType.APPROVE)
            ?: throw PendingDependencyException("원본 승인 건 미도착 (의존성 대기). key: $paymentKey")
    }

    private fun loadOriginalLedgerOrThrow(originalApproveRaw: SettlementRawData): SettlementLedger {
        return ledgerRepository.findByRawEventId(originalApproveRaw.eventId)
            ?: throw PendingDependencyException("원본 Ledger 미생성 (의존성 대기). event: ${originalApproveRaw.eventId}")
    }

    private fun sumAlreadyCancelledAmount(originalApproveTransactionId: Long): Long {
        return ledgerRepository.findAllByOriginalPaymentTxId(originalApproveTransactionId)
            .sumOf { -it.amount }
    }

    private fun validateCancelAmountNotExceeded(
        raw: SettlementRawData,
        originalApprovedAmount: Long,
        alreadyCancelledAmount: Long
    ) {
        val remainingCancelableAmount = originalApprovedAmount - alreadyCancelledAmount
        if (raw.amount > remainingCancelableAmount) {
            throw NonRetryableException(
                "누적 취소 금액이 원승인 금액을 초과합니다. " +
                        "원승인=${originalApprovedAmount}, " +
                        "기취소=${alreadyCancelledAmount}, " +
                        "잔여=${remainingCancelableAmount}, " +
                        "이번취소=${raw.amount}, " +
                        "paymentKey=${raw.paymentKey}"
            )
        }
    }

    private fun buildCancelLedger(
        raw: SettlementRawData,
        originalApproveRaw: SettlementRawData,
        originalLedger: SettlementLedger
    ): SettlementLedger {
        val hostFee = calculateFee(raw.amount, HOST_FEE_RATE)
        val totalFee = calculateFee(raw.amount, originalLedger.policyFeeRate)
        val serviceFee = totalFee - hostFee

        val cancelSettlementBaseDate = raw.eventOccurredAt
            .plusDays(originalLedger.policySettlementCycleDays.toLong())
            .toLocalDate()

        return SettlementLedger.create(
            raw = raw,
            originalPaymentTxId = originalApproveRaw.transactionId,
            hostFee = hostFee,
            serviceFee = serviceFee,
            settlementBaseDate = cancelSettlementBaseDate,
            settlementPolicyId = originalLedger.settlementPolicyId,
            policyFeeRate = originalLedger.policyFeeRate,
            policySettlementCycleDays = originalLedger.policySettlementCycleDays
        )
    }

    private fun calculateFee(amount: Long, feeRate: BigDecimal): Long {
        return (amount.toBigDecimal() * feeRate)
            .setScale(0, RoundingMode.HALF_UP).toLong()
    }

    private fun handleDataIntegrityViolation(raw: SettlementRawData, e: DataIntegrityViolationException) {
        if (ledgerRepository.findByRawEventId(raw.eventId) != null) {
            structuredLogger.info(
                logType = LogType.FLOW,
                result = LogResult.SKIP,
                payload = mapOf("reason" to "idempotent_after_conflict", "eventId" to raw.eventId)
            )
            return
        }

        if (e is DuplicateKeyException) {
            throw RetryableException("중복 키 충돌. 동시 삽입 경합 가능성. 재시도 가능. eventId: ${raw.eventId}", e)
        }

        throw NonRetryableException("DB 제약 조건 위반 (FK, NOT NULL 등). 재시도 불가. eventId: ${raw.eventId}", e)
    }
}
