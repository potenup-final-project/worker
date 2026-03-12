package com.pg.worker.webhook.reconciliation.infra

import com.pg.worker.webhook.reconciliation.domain.WebhookMismatchType
import com.pg.worker.webhook.reconciliation.domain.WebhookReconciliationResult
import com.pg.worker.webhook.reconciliation.domain.WebhookReconciliationStatus
import com.pg.worker.webhook.reconciliation.application.WebhookReconciliationProperties
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

@Repository
@ConditionalOnProperty(prefix = "webhook.recon", name = ["enabled"], havingValue = "true")
class WebhookReconciliationResultStore(
    @Qualifier("workerJdbcTemplate")
    private val jdbcTemplate: NamedParameterJdbcTemplate,
    private val properties: WebhookReconciliationProperties,
) {
    init {
        if (properties.autoDdl) {
            jdbcTemplate.jdbcTemplate.execute(
                """
                CREATE TABLE IF NOT EXISTS webhook_reconciliation_results (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    reconciliation_date DATE NOT NULL,
                    merchant_id BIGINT NOT NULL,
                    mismatch_type VARCHAR(30) NOT NULL,
                    status VARCHAR(20) NOT NULL,
                    event_id CHAR(36) NULL,
                    delivery_id BIGINT NULL,
                    endpoint_id BIGINT NULL,
                    fingerprint VARCHAR(200) NOT NULL,
                    reason VARCHAR(500) NULL,
                    meta_json JSON NULL,
                    created_at DATETIME(3) NOT NULL,
                    updated_at DATETIME(3) NOT NULL,
                    resolved_at DATETIME(3) NULL,
                    INDEX idx_wh_recon_date_status (reconciliation_date, status),
                    INDEX idx_wh_recon_type_status (mismatch_type, status),
                    INDEX idx_wh_recon_merchant_date (merchant_id, reconciliation_date),
                    UNIQUE KEY uq_wh_recon_fingerprint_status (fingerprint, status)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """.trimIndent()
            )
        }
    }

    fun existsOpenByFingerprint(fingerprint: String): Boolean {
        return jdbcTemplate.queryForObject(
            """
            SELECT EXISTS(
                SELECT 1
                FROM webhook_reconciliation_results
                WHERE fingerprint = :fingerprint
                  AND status = :status
            )
            """.trimIndent(),
            mapOf(
                "fingerprint" to fingerprint,
                "status" to WebhookReconciliationStatus.OPEN.name,
            ),
            Boolean::class.java,
        ) ?: false
    }

    fun insertOpen(result: WebhookReconciliationResult): Boolean {
        return try {
            jdbcTemplate.update(
                """
                INSERT INTO webhook_reconciliation_results (
                    reconciliation_date,
                    merchant_id,
                    mismatch_type,
                    status,
                    event_id,
                    delivery_id,
                    endpoint_id,
                    fingerprint,
                    reason,
                    meta_json,
                    created_at,
                    updated_at,
                    resolved_at
                ) VALUES (
                    :reconciliationDate,
                    :merchantId,
                    :mismatchType,
                    :status,
                    :eventId,
                    :deliveryId,
                    :endpointId,
                    :fingerprint,
                    :reason,
                    :metaJson,
                    :createdAt,
                    :updatedAt,
                    :resolvedAt
                )
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("reconciliationDate", result.reconciliationDate)
                    .addValue("merchantId", result.merchantId)
                    .addValue("mismatchType", result.mismatchType.name)
                    .addValue("status", result.status.name)
                    .addValue("eventId", result.eventId?.toString())
                    .addValue("deliveryId", result.deliveryId)
                    .addValue("endpointId", result.endpointId)
                    .addValue("fingerprint", result.fingerprint)
                    .addValue("reason", result.reason)
                    .addValue("metaJson", result.metaJson)
                    .addValue("createdAt", result.createdAt)
                    .addValue("updatedAt", result.updatedAt)
                    .addValue("resolvedAt", result.resolvedAt),
            )
            true
        } catch (_: DataIntegrityViolationException) {
            false
        }
    }

    fun findOpenByTypesSince(types: List<WebhookMismatchType>, since: LocalDate): List<WebhookReconciliationResult> {
        return findOpenByTypesSince(types, since, 0, Int.MAX_VALUE)
    }

    fun findOpenByTypesSince(types: List<WebhookMismatchType>, since: LocalDate, offset: Int, limit: Int): List<WebhookReconciliationResult> {
        if (types.isEmpty()) return emptyList()
        return jdbcTemplate.query(
            """
            SELECT *
            FROM webhook_reconciliation_results
            WHERE status = :status
              AND mismatch_type IN (:types)
              AND reconciliation_date >= :since
            ORDER BY created_at ASC
            LIMIT :limit OFFSET :offset
            """.trimIndent(),
            mapOf(
                "status" to WebhookReconciliationStatus.OPEN.name,
                "types" to types.map { it.name },
                "since" to since,
                "limit" to limit,
                "offset" to offset,
            ),
            rowMapper,
        )
    }

    fun resolveIfOpen(fingerprint: String, reason: String): Int {
        val resolvedFingerprint = "$fingerprint:resolved:${System.currentTimeMillis()}"
        return jdbcTemplate.update(
            """
            UPDATE webhook_reconciliation_results
            SET status = :resolved,
                fingerprint = :resolvedFingerprint,
                reason = :reason,
                resolved_at = :resolvedAt,
                updated_at = :updatedAt
            WHERE fingerprint = :fingerprint
              AND status = :open
            """.trimIndent(),
            mapOf(
                "resolved" to WebhookReconciliationStatus.RESOLVED.name,
                "resolvedFingerprint" to resolvedFingerprint,
                "reason" to reason,
                "resolvedAt" to LocalDateTime.now(),
                "updatedAt" to LocalDateTime.now(),
                "fingerprint" to fingerprint,
                "open" to WebhookReconciliationStatus.OPEN.name,
            ),
        )
    }

    fun countOpenByType(type: WebhookMismatchType): Long {
        return jdbcTemplate.queryForObject(
            """
            SELECT COUNT(1)
            FROM webhook_reconciliation_results
            WHERE status = :status
              AND mismatch_type = :mismatchType
            """.trimIndent(),
            mapOf(
                "status" to WebhookReconciliationStatus.OPEN.name,
                "mismatchType" to type.name,
            ),
            Long::class.java,
        ) ?: 0L
    }

    private val rowMapper = RowMapper { rs: ResultSet, _: Int ->
        WebhookReconciliationResult(
            id = rs.getLong("id"),
            reconciliationDate = rs.getDate("reconciliation_date").toLocalDate(),
            merchantId = rs.getLong("merchant_id"),
            mismatchType = WebhookMismatchType.valueOf(rs.getString("mismatch_type")),
            status = WebhookReconciliationStatus.valueOf(rs.getString("status")),
            eventId = rs.getString("event_id")?.let { UUID.fromString(it) },
            deliveryId = rs.getLong("delivery_id").takeIf { !rs.wasNull() },
            endpointId = rs.getLong("endpoint_id").takeIf { !rs.wasNull() },
            fingerprint = rs.getString("fingerprint"),
            reason = rs.getString("reason"),
            metaJson = rs.getString("meta_json"),
            createdAt = rs.getTimestamp("created_at").toLocalDateTime(),
            updatedAt = rs.getTimestamp("updated_at").toLocalDateTime(),
            resolvedAt = rs.getTimestamp("resolved_at")?.toLocalDateTime(),
        )
    }
}
