package com.pg.worker.webhook.infra.persistence

import com.pg.worker.webhook.application.usecase.repository.WebhookEndpointReadRepository
import com.pg.worker.webhook.domain.QWebhookEndpoint
import com.pg.worker.webhook.domain.WebhookEndpoint
import com.querydsl.jpa.impl.JPAQueryFactory
import org.springframework.stereotype.Repository

@Repository
class WebhookEndpointReadRepositoryImpl(
    private val queryFactory: JPAQueryFactory,
) : WebhookEndpointReadRepository {
    private val qEndpoint = QWebhookEndpoint.webhookEndpoint

    override fun findByMerchantIdAndEndpointIds(merchantId: Long, endpointIds: Collection<Long>): List<WebhookEndpoint> {
        if (endpointIds.isEmpty()) return emptyList()
        return queryFactory
            .selectFrom(qEndpoint)
            .where(
                qEndpoint.merchantId.eq(merchantId),
                qEndpoint.endpointId.`in`(endpointIds),
            )
            .fetch()
    }
}
