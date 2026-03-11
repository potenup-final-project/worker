package com.pg.worker.webhook.reconciliation.domain

data class EndpointStatsProjection(
    val endpointId: Long,
    val merchantId: Long,
    val total: Long,
    val deadCount: Long,
    val successCount: Long,
) {
    val deadRate: Double
        get() = if (total > 0) deadCount.toDouble() / total else 0.0
}
