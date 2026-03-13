package com.pg.worker.global.logging.context

enum class WorkerResult {
    SUCCESS,
    FAIL,
    RETRY,
    DLQ,
    SKIPPED,
}
