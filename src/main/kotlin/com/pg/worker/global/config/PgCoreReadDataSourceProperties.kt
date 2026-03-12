package com.pg.worker.global.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "pgcore.read-datasource")
data class PgCoreReadDataSourceProperties(
    val url: String,
    val username: String,
    val password: String,
    val driverClassName: String,
)
