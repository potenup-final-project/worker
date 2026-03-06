package com.pg.worker.webhook.application.usecase.repository

import com.pg.worker.webhook.domain.WebhookEndpoint

interface WebhookEndpointReadRepository {
    fun findByMerchantIdAndEndpointIds(merchantId: Long, endpointIds: Collection<Long>): List<WebhookEndpoint>
}
