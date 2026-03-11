package com.pg.worker.global.config

import com.zaxxer.hikari.HikariDataSource
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

@Configuration
@ConditionalOnProperty(prefix = "webhook.recon", name = ["enabled"], havingValue = "true")
class PgCoreReadDataSourceConfig {

    @Bean(name = ["pgCoreReadJdbcTemplate"])
    fun pgCoreReadJdbcTemplate(properties: PgCoreReadDataSourceProperties): NamedParameterJdbcTemplate {
        val dataSource = HikariDataSource()
        dataSource.jdbcUrl = properties.url
        dataSource.username = properties.username
        dataSource.password = properties.password
        dataSource.driverClassName = properties.driverClassName
        dataSource.maximumPoolSize = 3
        dataSource.minimumIdle = 1
        dataSource.isReadOnly = true
        dataSource.connectionTimeout = 3000
        dataSource.idleTimeout = 600000
        dataSource.poolName = "pgcore-read-pool"
        return NamedParameterJdbcTemplate(dataSource)
    }
}
