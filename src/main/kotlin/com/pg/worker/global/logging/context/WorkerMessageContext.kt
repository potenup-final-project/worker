package com.pg.worker.global.logging.context

data class WorkerMessageContext(
    val traceId: String,
    val orderFlowId: String,
    val eventType: String,
    val messageId: String?,
    val queue: String?,
    val topic: String?,
    val consumer: String,
    val retryCount: Int? = null,
    val redelivered: Boolean? = null,
)
