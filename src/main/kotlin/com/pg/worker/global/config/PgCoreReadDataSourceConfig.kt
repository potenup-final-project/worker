package com.pg.worker.global.config

import com.zaxxer.hikari.HikariDataSource
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import javax.sql.DataSource

@Configuration
@ConditionalOnProperty(prefix = "webhook.recon", name = ["enabled"], havingValue = "true")
class PgCoreReadDataSourceConfig {

    @Bean(name = ["pgCoreReadDataSource"])
    fun pgCoreReadDataSource(properties: PgCoreReadDataSourceProperties): DataSource {
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
        return dataSource
    }

    @Bean(name = ["pgCoreReadJdbcTemplate"])
    fun pgCoreReadJdbcTemplate(
        @Qualifier("pgCoreReadDataSource") dataSource: DataSource,
    ): NamedParameterJdbcTemplate = NamedParameterJdbcTemplate(dataSource)
}
