package com.pg.worker.webhook.reconciliation.infra

import com.pg.worker.webhook.domain.WebhookDeliveryStatus
import com.pg.worker.webhook.reconciliation.domain.EndpointStatsProjection
import com.pg.worker.webhook.reconciliation.domain.StaleDeliveryProjection
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

@Repository
@ConditionalOnProperty(prefix = "webhook.recon", name = ["enabled"], havingValue = "true")
class WebhookDeliveryReconciliationReader(
    private val jdbcTemplate: NamedParameterJdbcTemplate,
) {
    fun findStaleFailedDeliveries(
        graceThreshold: LocalDateTime,
        ageThreshold: LocalDateTime,
        offset: Int,
        limit: Int,
    ): List<StaleDeliveryProjection> {
        return jdbcTemplate.query(
            """
            SELECT delivery_id, endpoint_id, merchant_id, event_id, attempt_no, last_error
            FROM webhook_deliveries
            WHERE status = :status
              AND next_attempt_at < :graceThreshold
              AND created_at < :ageThreshold
            ORDER BY created_at ASC
            LIMIT :limit OFFSET :offset
            """.trimIndent(),
            mapOf(
                "status" to WebhookDeliveryStatus.FAILED.name,
                "graceThreshold" to graceThreshold,
                "ageThreshold" to ageThreshold,
                "limit" to limit,
                "offset" to offset,
            ),
        ) { rs, _ ->
            StaleDeliveryProjection(
                deliveryId = rs.getLong("delivery_id"),
                endpointId = rs.getLong("endpoint_id"),
                merchantId = rs.getLong("merchant_id"),
                eventId = UUID.fromString(rs.getString("event_id")),
                attemptNo = rs.getInt("attempt_no"),
                lastError = rs.getString("last_error"),
            )
        }
    }

    fun aggregateEndpointStats(since: LocalDate, minSample: Int, offset: Int, limit: Int): List<EndpointStatsProjection> {
        return jdbcTemplate.query(
            """
            SELECT endpoint_id,
                   merchant_id,
                   COUNT(*) AS total,
                   SUM(CASE WHEN status = 'DEAD' THEN 1 ELSE 0 END) AS dead_count,
                   SUM(CASE WHEN status = 'SUCCESS' THEN 1 ELSE 0 END) AS success_count
            FROM webhook_deliveries
            WHERE created_at >= :since
            GROUP BY endpoint_id, merchant_id
            HAVING COUNT(*) >= :minSample
            ORDER BY dead_count DESC
            LIMIT :limit OFFSET :offset
            """.trimIndent(),
            mapOf(
                "since" to since.atStartOfDay(),
                "minSample" to minSample,
                "limit" to limit,
                "offset" to offset,
            ),
        ) { rs, _ ->
            EndpointStatsProjection(
                endpointId = rs.getLong("endpoint_id"),
                merchantId = rs.getLong("merchant_id"),
                total = rs.getLong("total"),
                deadCount = rs.getLong("dead_count"),
                successCount = rs.getLong("success_count"),
            )
        }
    }

    fun aggregateEndpointStats(endpointId: Long, since: LocalDate): EndpointStatsProjection? {
        return jdbcTemplate.query(
            """
            SELECT endpoint_id,
                   merchant_id,
                   COUNT(*) AS total,
                   SUM(CASE WHEN status = 'DEAD' THEN 1 ELSE 0 END) AS dead_count,
                   SUM(CASE WHEN status = 'SUCCESS' THEN 1 ELSE 0 END) AS success_count
            FROM webhook_deliveries
            WHERE created_at >= :since
              AND endpoint_id = :endpointId
            GROUP BY endpoint_id, merchant_id
            """.trimIndent(),
            mapOf(
                "since" to since.atStartOfDay(),
                "endpointId" to endpointId,
            ),
        ) { rs, _ ->
            EndpointStatsProjection(
                endpointId = rs.getLong("endpoint_id"),
                merchantId = rs.getLong("merchant_id"),
                total = rs.getLong("total"),
                deadCount = rs.getLong("dead_count"),
                successCount = rs.getLong("success_count"),
            )
        }.firstOrNull()
    }

    fun aggregateEndpointStatsByIds(endpointIds: List<Long>, since: LocalDate): Map<Long, EndpointStatsProjection> {
        if (endpointIds.isEmpty()) return emptyMap()
        return jdbcTemplate.query(
            """
            SELECT endpoint_id,
                   merchant_id,
                   COUNT(*) AS total,
                   SUM(CASE WHEN status = 'DEAD' THEN 1 ELSE 0 END) AS dead_count,
                   SUM(CASE WHEN status = 'SUCCESS' THEN 1 ELSE 0 END) AS success_count
            FROM webhook_deliveries
            WHERE created_at >= :since
              AND endpoint_id IN (:endpointIds)
            GROUP BY endpoint_id, merchant_id
            """.trimIndent(),
            mapOf(
                "since" to since.atStartOfDay(),
                "endpointIds" to endpointIds,
            ),
        ) { rs, _ ->
            EndpointStatsProjection(
                endpointId = rs.getLong("endpoint_id"),
                merchantId = rs.getLong("merchant_id"),
                total = rs.getLong("total"),
                deadCount = rs.getLong("dead_count"),
                successCount = rs.getLong("success_count"),
            )
        }.associateBy { it.endpointId }
    }

    fun findExistingEventIds(eventIds: List<UUID>): Set<UUID> {
        if (eventIds.isEmpty()) return emptySet()
        val rows = jdbcTemplate.queryForList(
            """
            SELECT DISTINCT event_id
            FROM webhook_deliveries
            WHERE event_id IN (:eventIds)
            """.trimIndent(),
            mapOf("eventIds" to eventIds.map { it.toString() }),
            String::class.java,
        )
        return rows.map { UUID.fromString(it) }.toSet()
    }

    fun findMerchantsWithActiveEndpoints(merchantIds: List<Long>): Set<Long> {
        if (merchantIds.isEmpty()) return emptySet()
        val rows = jdbcTemplate.queryForList(
            """
            SELECT DISTINCT merchant_id
            FROM merchant_webhook_endpoints
            WHERE merchant_id IN (:merchantIds)
              AND is_active = true
            """.trimIndent(),
            mapOf("merchantIds" to merchantIds),
            Long::class.javaObjectType,
        )
        return rows.toSet()
    }

    fun existsByEventId(eventId: UUID): Boolean {
        return jdbcTemplate.queryForObject(
            """
            SELECT EXISTS(
                SELECT 1
                FROM webhook_deliveries
                WHERE event_id = :eventId
            )
            """.trimIndent(),
            mapOf("eventId" to eventId.toString()),
            Boolean::class.java,
        ) ?: false
    }

    fun findStatusByIds(deliveryIds: List<Long>): Map<Long, WebhookDeliveryStatus> {
        if (deliveryIds.isEmpty()) return emptyMap()
        return jdbcTemplate.query(
            """
            SELECT delivery_id, status
            FROM webhook_deliveries
            WHERE delivery_id IN (:deliveryIds)
            """.trimIndent(),
            mapOf("deliveryIds" to deliveryIds),
        ) { rs, _ ->
            rs.getLong("delivery_id") to WebhookDeliveryStatus.valueOf(rs.getString("status"))
        }.toMap()
    }
}
