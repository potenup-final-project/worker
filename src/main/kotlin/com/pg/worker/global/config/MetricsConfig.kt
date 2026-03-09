package com.pg.worker.global.config

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class MetricsConfig {
    @Bean
    @ConditionalOnMissingBean(MeterRegistry::class)
    fun meterRegistry(): MeterRegistry = SimpleMeterRegistry()
}
