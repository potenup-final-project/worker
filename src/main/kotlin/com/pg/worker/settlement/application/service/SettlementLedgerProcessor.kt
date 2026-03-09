package com.pg.worker.settlement.application.service

import com.pg.worker.settlement.application.repository.SettlementLedgerRepository
import com.pg.worker.settlement.application.repository.SettlementPolicyRepository
import com.pg.worker.settlement.application.repository.SettlementRawDataRepository
import com.pg.worker.settlement.domain.*
import com.pg.worker.settlement.domain.exception.*
import org.slf4j.LoggerFactory
import org.springframework.dao.ConcurrencyFailureException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.TransientDataAccessException
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.math.RoundingMode

@Component
class SettlementLedgerProcessor(
    private val rawRepository: SettlementRawDataRepository,
    private val ledgerRepository: SettlementLedgerRepository,
    private val policyRepository: SettlementPolicyRepository
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 정산 처리 로직 (독립 트랜잭션)
     * - 예외 분류:
     *  (1) DataIntegrityViolationException: 멱등성 충돌인지,
     *  (2) ConcurrencyFailureException: 동시성 충돌(Deadlock 등)으로 재시도 가능,
     *  (3) TransientDataAccessException: 기술적 일시 장애로 재시도 가능,
     *  (4) 그 외 예외는 예상치 못한 시스템 오류로 간주하여 재시도 불가로 처리
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun process(rawId: Long) {
        val raw = rawRepository.findById(rawId) ?: run {
            log.warn("[Settlement] [Processor] 처리를 위한 Raw 데이터가 존재하지 않습니다. rawId={} (원인: 잘못된 ID 전달 또는 DB 복제 지연 등)", rawId)
            return
        }

        // 멱등 성공 확인: 이미 Ledger가 있다면 성공으로 간주하고 중단
        if (ledgerRepository.findByRawEventId(raw.eventId) != null) {
            log.info("[Settlement] [Idempotent] 이미 처리된 Ledger가 존재합니다. eventId={}", raw.eventId)
            raw.markProcessed()
            rawRepository.save(raw)
            return
        }

        // 비즈니스 무결성 확인: 잘못된 타입은 재시도해도 성공 가능성이 없으므로 즉시 실패 처리
        if (raw.transactionType == TransactionType.UNKNOWN) {
            throw NonRetryableException("잘못된 거래 타입으로 처리가 불가능합니다. eventId: ${raw.eventId}")
        }

        try {
            when (raw.transactionType) {
                TransactionType.APPROVE -> handleApprove(raw)
                TransactionType.CANCEL -> handleCancel(raw)
                else -> throw NonRetryableException("지원하지 않는 거래 타입: ${raw.transactionType}")
            }
        } catch (e: DataIntegrityViolationException) {
            handleDataIntegrityViolation(raw, e)
        } catch (e: ConcurrencyFailureException) {
            throw RetryableException("동시성 충돌(Deadlock 등)로 인한 재시도 가능. eventId: ${raw.eventId}", e)
        } catch (e: TransientDataAccessException) {
            // 기술적 일시 장애 (Lock Timeout, Deadlock 등)
            throw RetryableException("DB 기술적 일시 장애로 인한 재시도 가능. eventId: ${raw.eventId}", e)
        }

        raw.markProcessed()
        rawRepository.save(raw)
    }

    /**
     * DataIntegrityViolationException 분류 로직
     */
    private fun handleDataIntegrityViolation(raw: SettlementRawData, e: DataIntegrityViolationException) {
        val retryLedger = ledgerRepository.findByRawEventId(raw.eventId)
        if (retryLedger != null) {
            log.info("[Settlement] [Idempotent] 동시성 충돌 후 Ledger 존재 확인(멱등 성공). eventId={}", raw.eventId)
            return
        }

        val message = e.message ?: ""
        if (message.contains("null", ignoreCase = true) || message.contains("constraint", ignoreCase = true)) {
            throw NonRetryableException("DB 제약 조건 위반 (Null 또는 스키마 오류). 재시도 불가. eventId: ${raw.eventId}", e)
        }

        throw RetryableException("원인 불명의 DB 제약 위반. 운영 확인 필요. eventId: ${raw.eventId}", e)
    }

    private fun handleApprove(raw: SettlementRawData) {
        val policy = policyRepository.findByMerchantId(raw.merchantId)
            ?: throw NonRetryableException("가맹점 정산 정책을 찾을 수 없습니다: ${raw.merchantId}")

        val fee = (raw.amount.toBigDecimal() * policy.feeRate)
            .setScale(0, RoundingMode.HALF_UP).toLong()
        val settlementAmount = raw.amount - fee
        val settlementBaseDate = raw.eventOccurredAt.plusDays(policy.settlementCycleDays.toLong())

        val ledger = SettlementLedger.create(
            raw = raw,
            originalPaymentTxId = null,
            fee = fee,
            settlementAmount = settlementAmount,
            settlementBaseDate = settlementBaseDate,
            policy = policy
        )
        ledgerRepository.save(ledger)
    }

    private fun handleCancel(raw: SettlementRawData) {
        // 취소는 원본 승인 거래를 참조해야 하므로, 원본 승인 거래와 Ledger가 존재하는지 확인
        val originalApproveRaw = rawRepository.findByPaymentKeyAndTransactionType(
            raw.paymentKey, TransactionType.APPROVE
        ) ?: throw PendingDependencyException("원본 승인 건 미도착 (의존성 대기). key: ${raw.paymentKey}")

        val originalLedger = ledgerRepository.findByRawEventId(originalApproveRaw.eventId)
            ?: throw PendingDependencyException("원본 Ledger 미생성 (의존성 대기). event: ${originalApproveRaw.eventId}")

        val policy = policyRepository.findByMerchantId(raw.merchantId)
            ?: throw NonRetryableException("가맹점 정산 정책 미설정")

        val fee = (raw.amount.toBigDecimal() * policy.feeRate)
            .setScale(0, RoundingMode.HALF_UP).toLong()

        val ledger = SettlementLedger.create(
            raw = raw,
            originalPaymentTxId = originalApproveRaw.transactionId,
            fee = -fee,
            settlementAmount = -(raw.amount - fee),
            settlementBaseDate = originalLedger.settlementBaseDate,
            policy = policy
        )
        ledgerRepository.save(ledger)
    }
}
