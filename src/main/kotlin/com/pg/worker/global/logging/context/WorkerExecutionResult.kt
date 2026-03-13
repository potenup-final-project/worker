package com.pg.worker.global.logging.context

data class WorkerExecutionResult<T>(
    val result: WorkerResult,
    val data: T? = null,
    val error: Throwable? = null,
)
