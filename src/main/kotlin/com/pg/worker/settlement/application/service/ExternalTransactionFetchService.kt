package com.pg.worker.settlement.application.service

import com.pg.worker.settlement.application.repository.ExternalSettlementClient
import com.pg.worker.settlement.application.repository.ExternalSettlementDetailRepository
import com.pg.worker.settlement.domain.ExternalSettlementDetail
import com.gop.logging.contract.StructuredLogger
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/**
 * 외부 정산 거래 내역 수집 서비스
 */
@Service
class ExternalTransactionFetchService(
    private val externalClient: ExternalSettlementClient,
    private val externalRepository: ExternalSettlementDetailRepository,
    private val log: StructuredLogger) {

    /**
     * 특정 날짜의 외부 데이터를 수집하여 DB에 저장 (동기화)
     */
    @Transactional
    fun fetchAndSync(baseDate: LocalDate) {
        log.info("[외부데이터수집] 시작. 기준일={}", baseDate)

        // 1. 외부 API 호출하여 전체 데이터 획득
        val externalDtos = try {
            externalClient.fetchExternalTransactions(baseDate = baseDate)
        } catch (e: Exception) {
            log.error("[외부데이터수집] API 호출 중 오류 발생. 기준일={}", baseDate, e)
            throw e
        }

        if (externalDtos.isEmpty()) {
            log.warn("[외부데이터수집] 수집된 데이터가 없습니다. 기준일={}", baseDate)
            return
        }

        // 2. 신규 데이터만 필터링 (중복 저장 방지)
        val newRecords = externalDtos
            .filterNot { externalRepository.existsByProviderTxId(it.providerTxId) }
            .map { dto ->
                ExternalSettlementDetail.create(
                    providerTxId = dto.providerTxId,
                    sourceSystem = dto.sourceSystem,
                    merchantId = dto.merchantId,
                    transactionType = dto.transactionType,
                    amount = dto.amount,
                    fee = dto.fee,
                    netAmount = dto.netAmount,
                    settlementBaseDate = baseDate,
                    payoutDate = dto.payoutDate,
                    occurredAt = dto.occurredAt,
                    rawPayload = dto.rawPayload
                )
            }

        // 3. DB 일괄 저장
        if (newRecords.isNotEmpty()) {
            externalRepository.saveAll(newRecords)
            log.info("[외부데이터수집] 완료. 신규 {}건 저장 (총 {}건 수집).", newRecords.size, externalDtos.size)
        } else {
            log.info("[외부데이터수집] 완료. 이미 모든 데이터가 최신 상태입니다.")
        }
    }
}
