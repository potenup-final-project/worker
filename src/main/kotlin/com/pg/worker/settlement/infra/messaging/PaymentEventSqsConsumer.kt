package com.pg.worker.settlement.infra.messaging

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.pg.worker.settlement.application.usecase.command.RecordSettlementCommandUseCase
import com.pg.worker.settlement.application.usecase.command.dto.RecordSettlementCommand
import io.awspring.cloud.sqs.annotation.SqsListener
import org.slf4j.LoggerFactory
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.stereotype.Component

@Component
class PaymentEventSqsConsumer(
    private val recordSettlementCommandUseCase: RecordSettlementCommandUseCase,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * SQS 메시지 소비 및 예외 처리 정책
     *
     * 1. Consumer 레벨 재시도 불가
     *    - JSON 파싱 실패, 메시지 계약 위반
     *    - 재시도해도 동일하게 실패하므로 Ack 처리하고 로그만 남긴다.
     *
     * 2. 애플리케이션 레벨 관리
     *    - Raw 저장 이후의 비즈니스 실패는 애플리케이션이 DB 상태로 관리한다.
     *    - 이 경우 record()는 정상 종료될 수 있고, consumer도 성공으로 간주한다.
     *
     * 3. Consumer 레벨 재시도 가능
     *    - record() 밖으로 예외가 전파됐다는 것은
     *      애플리케이션이 실패 상태를 정상 기록하지 못했거나,
     *      기술적 장애로 인해 처리 완료를 확정할 수 없다는 뜻이다.
     *    - 이 경우 예외를 다시 던져 SQS 재시도 / DLQ 정책을 타게 한다.
     */
    @SqsListener("payment-event-queue")
    fun consume(@Payload message: String) {
        log.info("[SQS-Consumer] payment-event-queue에서 메시지를 수신했습니다.")

        val command = try {
            objectMapper.readValue(message, RecordSettlementCommand::class.java)
        } catch (e: JsonProcessingException) {
            log.error("[SQS-Consumer] [재시도 불가] JSON 파싱에 실패했습니다.", e)
            return
        } catch (e: Exception) {
            log.error("[SQS-Consumer] [재시도 불가] 메시지 매핑에 실패했습니다.", e)
            return
        }

        log.info(
            "[SQS-Consumer] 메시지 파싱 완료. eventId={}, transactionType={}",
            command.eventId,
            command.transactionType
        )

        try {
            recordSettlementCommandUseCase.record(command)
            log.info("[SQS-Consumer] 메시지 처리 완료. eventId={}", command.eventId)
        } catch (e: Exception) {
            /**
             * 애플리케이션 레벨에서 상태 기록까지 정상 종료하지 못한 경우로 간주
             * 따라서 Ack 하면 안 되고, SQS 재시도 / DLQ 정책을 타도록 재시도 처리
             */
            log.error(
                "[SQS-Consumer] [재시도 대상] 처리에 실패해 SQS 재시도를 유도합니다. eventId={}",
                command.eventId,
                e
            )
            throw e
        }
    }
}
