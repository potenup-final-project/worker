package com.pg.worker.global.logging.annotation

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class BusinessLog(
    val category: String,
    val event: String,
    val logOnSuccess: Boolean = true,
    val logOnFailure: Boolean = true,
)
