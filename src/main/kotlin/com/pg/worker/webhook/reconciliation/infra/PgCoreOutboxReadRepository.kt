package com.pg.worker.webhook.reconciliation.infra

import com.pg.worker.webhook.reconciliation.domain.PublishedOutboxEvent
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

@Repository
@ConditionalOnProperty(prefix = "webhook.recon", name = ["enabled"], havingValue = "true")
class PgCoreOutboxReadRepository(
    @Qualifier("pgCoreReadJdbcTemplate")
    private val jdbcTemplate: NamedParameterJdbcTemplate,
) {
    fun findPublishedEventsByDate(
        targetDate: LocalDate,
        cutoff: LocalDateTime,
        offset: Int,
        limit: Int,
    ): List<PublishedOutboxEvent> {
        val from = targetDate.atStartOfDay()
        val to = targetDate.plusDays(1).atStartOfDay()

        return jdbcTemplate.query(
            """
            SELECT event_id, merchant_id, event_type
            FROM outbox_events
            WHERE status = 'PUBLISHED'
              AND created_at >= :from
              AND created_at < :to
              AND created_at <= :cutoff
            ORDER BY created_at ASC
            LIMIT :limit OFFSET :offset
            """.trimIndent(),
            mapOf(
                "from" to from,
                "to" to to,
                "cutoff" to cutoff,
                "limit" to limit,
                "offset" to offset,
            ),
        ) { rs, _ ->
            PublishedOutboxEvent(
                eventId = UUID.fromString(rs.getString("event_id")),
                merchantId = rs.getLong("merchant_id"),
                eventType = rs.getString("event_type"),
            )
        }
    }
}
