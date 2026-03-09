package com.pg.worker.settlement.application.usecase.query.dto

enum class SettlementProgressStage(
    val description: String,
    val incompleteReason: String
) {
    PAYMENT_CONFIRMED("결제 확정", "성공 거래가 아닙니다"),
    RAW_RECORDED("정산 원본 기록", "정산 원본 데이터 미기록"),
    LEDGER_CREATED("정산 원장 생성", "정산 원장 미생성"),
    AGGREGATED("정산 집계 완료", "정산 집계 미반영"),
    READY_FOR_PAYOUT("지급 대기", "지급 기능 미구현"),
    PAID_OUT("지급 완료", "지급 기능 미구현")
}
