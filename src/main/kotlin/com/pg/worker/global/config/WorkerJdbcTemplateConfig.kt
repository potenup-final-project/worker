package com.pg.worker.global.config

import com.zaxxer.hikari.HikariDataSource
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

@Configuration
class WorkerJdbcTemplateConfig {

    @Bean(name = ["workerJdbcTemplate"])
    fun workerJdbcTemplate(
        @Value("\${spring.datasource.url}") url: String,
        @Value("\${spring.datasource.username}") username: String,
        @Value("\${spring.datasource.password}") password: String,
        @Value("\${spring.datasource.driver-class-name}") driverClassName: String,
    ): NamedParameterJdbcTemplate {
        val dataSource = HikariDataSource()
        dataSource.jdbcUrl = url
        dataSource.username = username
        dataSource.password = password
        dataSource.driverClassName = driverClassName
        dataSource.maximumPoolSize = 4
        dataSource.minimumIdle = 1
        dataSource.poolName = "worker-write-pool"
        dataSource.isReadOnly = false
        return NamedParameterJdbcTemplate(dataSource)
    }
}
