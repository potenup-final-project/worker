package com.pg.worker.settlement.domain

enum class SettlementReconciliationResultType {
    /** 완전 일치: amount + transactionType 모두 일치 */
    MATCHED,

    /** 원금 불일치: 양쪽 존재하나 amount 다름 */
    AMOUNT_MISMATCH,

    /** 부분 일치: amount는 같으나 transactionType 불일치 */
    PARTIALLY_MATCHED,

    /** 내부 누락: 외부 정산 파일에는 존재하나 내부 RawData 없음 */
    MISSING_INTERNAL,

    /** 외부 누락: 내부 RawData는 존재하나 외부 정산 파일에 없음 */
    MISSING_EXTERNAL,

    /** 내부 중복: 동일 providerTxId로 내부 RawData 2건 이상 */
    DUPLICATED_INTERNAL,

    /** 외부 중복: 동일 providerTxId로 외부 Record 2건 이상 */
    DUPLICATED_EXTERNAL,

    /** 수수료 불일치: 카드사 수수료와 내부 계산 수수료가 다름 */
    FEE_MISMATCH
}
