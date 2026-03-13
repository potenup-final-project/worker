package com.pg.worker.settlement.consumer.dto

import java.util.UUID

/**
 * SQS 메시지 전체 구조 (Envelope)
 * 웹훅(WebhookDispatchMessage)과 동일한 구조를 가집니다.
 */
data class SettlementDispatchMessage(
    val schemaVersion: Int = 1,
    val messageId: String,
    val traceId: String? = null,
    val occurredAt: String,
    val eventType: String,
    val eventId: UUID,
    val merchantId: Long,
    val aggregateId: Long,
    val payload: String // 실제 정산 데이터는 JSON 문자열로 들어옴
)

/**
 * payload 필드 내부에 들어있는 실제 비즈니스 데이터 구조
 */
data class SettlementEventPayload(
    val paymentKey: String,
    val transactionId: Long,
    val orderId: String,
    val providerTxId: String,
    val transactionType: String,
    val amount: Long,
)
