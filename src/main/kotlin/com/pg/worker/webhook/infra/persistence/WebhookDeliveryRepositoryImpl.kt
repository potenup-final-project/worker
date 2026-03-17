package com.pg.worker.webhook.infra.persistence

import com.pg.worker.webhook.application.usecase.dto.ClaimedDelivery
import com.pg.worker.webhook.application.usecase.repository.WebhookDeliveryStateRepository
import com.pg.worker.webhook.application.usecase.repository.dto.DeliverySendOutcome
import com.pg.worker.webhook.domain.QWebhookDelivery
import com.pg.worker.webhook.domain.WebhookDeliveryStatus
import com.querydsl.jpa.impl.JPAQueryFactory
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import com.gop.logging.contract.StructuredLogger
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.LocalDateTime
import java.util.UUID

// webhook_deliveries claim/mutation QueryDSL 리포지토리 (bulkInsertIgnore만 네이티브 쿼리)
@Repository
class WebhookDeliveryRepositoryImpl(
    private val queryFactory: JPAQueryFactory,
    private val entityManager: EntityManager,
    private val jdbcTemplate: JdbcTemplate,
    private val log: StructuredLogger) : WebhookDeliveryStateRepository {

    private val qDelivery = QWebhookDelivery.webhookDelivery

    // TX1 (REQUIRES_NEW): due delivery batch claim 후 IN_PROGRESS로 전이
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun claimDueBatch(batchSize: Int): List<ClaimedDelivery> {
        val now = LocalDateTime.now()

        val rows = queryFactory
            .selectFrom(qDelivery)
            .where(
                qDelivery.status.`in`(WebhookDeliveryStatus.READY, WebhookDeliveryStatus.FAILED),
                qDelivery.nextAttemptAt.loe(now),
            )
            .orderBy(qDelivery.nextAttemptAt.asc(), qDelivery.deliveryId.asc())
            .limit(batchSize.toLong())
            .setLockMode(LockModeType.PESSIMISTIC_WRITE)
            .setHint(JPA_LOCK_TIMEOUT_HINT, SKIP_LOCKED)
            .fetch()

        if (rows.isEmpty()) return emptyList()

        val ids = rows.map { it.deliveryId }
        val updated = queryFactory.update(qDelivery)
            .set(qDelivery.status, WebhookDeliveryStatus.IN_PROGRESS)
            .set(qDelivery.attemptNo, qDelivery.attemptNo.add(1))
            .set(qDelivery.updatedAt, LocalDateTime.now())
            .where(
                qDelivery.deliveryId.`in`(ids),
                qDelivery.status.`in`(WebhookDeliveryStatus.READY, WebhookDeliveryStatus.FAILED),
            )
            .execute()

        if (updated < rows.size) {
            log.warn("[DeliveryClaim] expected={} updated={}: 일부 row가 sweeper에 의해 선점됨", rows.size, updated)
        }

        return rows.map {
            ClaimedDelivery(
                deliveryId = it.deliveryId,
                endpointId = it.endpointId,
                messageId = it.messageId,
                traceId = it.traceId,
                eventType = it.eventType,
                eventId = it.eventId,
                merchantId = it.merchantId,
                payloadSnapshot = it.payloadSnapshot,
                attemptNo = it.attemptNo + 1,
            )
        }
    }

    // INSERT IGNORE: 중복 (event_id, endpoint_id) 무시하는 멱등 insert (네이티브 쿼리)
    // REQUIRED: 외부 TX가 있으면 참여, 없으면 신규 TX 생성 (직접 호출·DeliveryCreationService 양쪽 지원)
    @Transactional(propagation = Propagation.REQUIRED)
    override fun bulkInsertIgnore(
        eventId: UUID,
        messageId: String,
        traceId: String?,
        eventType: String,
        merchantId: Long,
        endpointIds: List<Long>,
        payloadSnapshot: String,
    ) {
        if (endpointIds.isEmpty()) return
        endpointIds.chunked(BULK_INSERT_CHUNK_SIZE).forEach { chunk ->
            val valuesSql = chunk.indices.joinToString(",") { index ->
                "(:eventId, :messageId, :traceId, :eventType, :endpointId$index, :merchantId, 'READY', $INITIAL_ATTEMPT_NO, NOW(), :payload, NOW(), NOW())"
            }

            val query = entityManager.createNativeQuery(
                """
                INSERT IGNORE INTO webhook_deliveries
                    (event_id, message_id, trace_id, event_type, endpoint_id, merchant_id, status, attempt_no, next_attempt_at, payload_snapshot, created_at, updated_at)
                VALUES $valuesSql
                """.trimIndent()
            )
                .setParameter("eventId", eventId.toString())
                .setParameter("messageId", messageId)
                .setParameter("traceId", traceId)
                .setParameter("eventType", eventType)
                .setParameter("merchantId", merchantId)
                .setParameter("payload", payloadSnapshot)

            chunk.forEachIndexed { index, endpointId ->
                query.setParameter("endpointId$index", endpointId)
            }

            query.executeUpdate()
        }
    }

    // REQUIRES_NEW: delivery를 SUCCESS로 전이
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun markSuccessNewTransaction(deliveryId: Long, httpStatus: Int, responseMs: Long) {
        queryFactory.update(qDelivery)
            .set(qDelivery.status, WebhookDeliveryStatus.SUCCESS)
            .set(qDelivery.lastHttpStatus, httpStatus)
            .set(qDelivery.lastResponseMs, responseMs)
            .setNull(qDelivery.lastError)
            .set(qDelivery.updatedAt, LocalDateTime.now())
            .where(qDelivery.deliveryId.eq(deliveryId))
            .execute()
    }

    // REQUIRES_NEW: delivery를 FAILED로 전이, last_http_status는 항상 갱신
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun markFailedNewTransaction(deliveryId: Long, httpStatus: Int?, errorCode: String, nextAt: LocalDateTime) {
        val clause = queryFactory.update(qDelivery)
            .set(qDelivery.status, WebhookDeliveryStatus.FAILED)
            .set(qDelivery.lastError, errorCode)
            .set(qDelivery.nextAttemptAt, nextAt)
            .set(qDelivery.updatedAt, LocalDateTime.now())

        if (httpStatus != null) clause.set(qDelivery.lastHttpStatus, httpStatus)
        else clause.setNull(qDelivery.lastHttpStatus)

        clause.where(qDelivery.deliveryId.eq(deliveryId)).execute()
    }

    // REQUIRES_NEW: delivery를 영구 실패(DEAD) 처리, last_http_status는 항상 갱신
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun markDeadNewTransaction(deliveryId: Long, httpStatus: Int?, errorCode: String) {
        val clause = queryFactory.update(qDelivery)
            .set(qDelivery.status, WebhookDeliveryStatus.DEAD)
            .set(qDelivery.lastError, errorCode)
            .set(qDelivery.updatedAt, LocalDateTime.now())

        if (httpStatus != null) clause.set(qDelivery.lastHttpStatus, httpStatus)
        else clause.setNull(qDelivery.lastHttpStatus)

        clause.where(qDelivery.deliveryId.eq(deliveryId)).execute()
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun applySendOutcomesNewTransaction(outcomes: List<DeliverySendOutcome>) {
        if (outcomes.isEmpty()) return

        val successes = outcomes.filter { it.status == WebhookDeliveryStatus.SUCCESS }
        if (successes.isNotEmpty()) {
            jdbcTemplate.batchUpdate(
                """
                UPDATE webhook_deliveries
                   SET status = 'SUCCESS',
                       last_http_status = ?,
                       last_response_ms = ?,
                       last_error = NULL,
                       updated_at = NOW(3)
                 WHERE delivery_id = ?
                """.trimIndent(),
                successes,
                successes.size,
            ) { ps, row ->
                ps.setInt(1, requireNotNull(row.httpStatus))
                ps.setLong(2, requireNotNull(row.responseMs))
                ps.setLong(3, row.deliveryId)
            }
        }

        val failed = outcomes.filter { it.status == WebhookDeliveryStatus.FAILED }
        if (failed.isNotEmpty()) {
            jdbcTemplate.batchUpdate(
                """
                UPDATE webhook_deliveries
                   SET status = 'FAILED',
                       last_http_status = ?,
                       last_error = ?,
                       next_attempt_at = ?,
                       updated_at = NOW(3)
                 WHERE delivery_id = ?
                """.trimIndent(),
                failed,
                failed.size,
            ) { ps, row ->
                if (row.httpStatus != null) ps.setInt(1, row.httpStatus) else ps.setNull(1, java.sql.Types.INTEGER)
                ps.setString(2, requireNotNull(row.errorCode))
                ps.setTimestamp(3, Timestamp.valueOf(requireNotNull(row.nextAttemptAt)))
                ps.setLong(4, row.deliveryId)
            }
        }

        val dead = outcomes.filter { it.status == WebhookDeliveryStatus.DEAD }
        if (dead.isNotEmpty()) {
            jdbcTemplate.batchUpdate(
                """
                UPDATE webhook_deliveries
                   SET status = 'DEAD',
                       last_http_status = ?,
                       last_error = ?,
                       updated_at = NOW(3)
                 WHERE delivery_id = ?
                """.trimIndent(),
                dead,
                dead.size,
            ) { ps, row ->
                if (row.httpStatus != null) ps.setInt(1, row.httpStatus) else ps.setNull(1, java.sql.Types.INTEGER)
                ps.setString(2, requireNotNull(row.errorCode))
                ps.setLong(3, row.deliveryId)
            }
        }
    }

    // REQUIRES_NEW: claimDueBatch에서 증가된 attempt_no를 원복하고 FAILED로 되돌림 (HTTP 미시도 claim 취소)
    // - status = IN_PROGRESS 가드: 레이스컨디션으로 이미 sweeper가 처리한 row는 건드리지 않음
    // - attempt_no >= 1 가드: attempt_no가 0인 비정상 row에서 음수로 내려가는 것을 방지
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun revertClaim(deliveryId: Long) {
        queryFactory.update(qDelivery)
            .set(qDelivery.status, WebhookDeliveryStatus.FAILED)
            .set(qDelivery.attemptNo, qDelivery.attemptNo.subtract(1))
            .set(qDelivery.nextAttemptAt, LocalDateTime.now())
            .set(qDelivery.updatedAt, LocalDateTime.now())
            .where(
                qDelivery.deliveryId.eq(deliveryId),
                qDelivery.status.eq(WebhookDeliveryStatus.IN_PROGRESS),
                qDelivery.attemptNo.goe(1),
            )
            .execute()
    }

    // REQUIRES_NEW: lease 만료된 IN_PROGRESS delivery를 FAILED로 복구
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun recoverExpiredLeases(leaseMinutes: Int): Int {
        val threshold = LocalDateTime.now().minusMinutes(leaseMinutes.toLong())
        return queryFactory.update(qDelivery)
            .set(qDelivery.status, WebhookDeliveryStatus.FAILED)
            .set(qDelivery.nextAttemptAt, LocalDateTime.now())
            .set(qDelivery.lastError, ERROR_LEASE_EXPIRED)
            .set(qDelivery.updatedAt, LocalDateTime.now())
            .where(
                qDelivery.status.eq(WebhookDeliveryStatus.IN_PROGRESS),
                qDelivery.updatedAt.lt(threshold),
            )
            .execute()
            .toInt()
    }

    companion object {
        private const val JPA_LOCK_TIMEOUT_HINT = "jakarta.persistence.lock.timeout"
        private const val SKIP_LOCKED = -2
        private const val INITIAL_ATTEMPT_NO = 0
        private const val BULK_INSERT_CHUNK_SIZE = 500
        private const val ERROR_LEASE_EXPIRED = "LEASE_EXPIRED"
    }
}
