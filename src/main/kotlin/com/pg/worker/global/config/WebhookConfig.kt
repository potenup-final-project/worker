package com.pg.worker.global.config

import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler

// 스케줄러 활성화 및 ObjectMapper 빈 등록
@Configuration
@EnableScheduling
class WebhookConfig {

    // 재시도 시 rawBody가 동일하게 유지되도록 직렬화 설정 고정
    @Bean
    fun webhookObjectMapper(): ObjectMapper = ObjectMapper()
        .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
        .configure(SerializationFeature.INDENT_OUTPUT, false)
        .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, true)

    @Bean(name = ["sqsPollingScheduler"])
    fun sqsPollingScheduler(): ThreadPoolTaskScheduler = ThreadPoolTaskScheduler().apply {
        poolSize = 1
        setThreadNamePrefix("sqs-poller-")
        setWaitForTasksToCompleteOnShutdown(true)
        setAwaitTerminationSeconds(30)
        initialize()
    }

    @Bean(name = ["webhookWorkerScheduler"])
    fun webhookWorkerScheduler(): ThreadPoolTaskScheduler = ThreadPoolTaskScheduler().apply {
        poolSize = 2
        setThreadNamePrefix("webhook-worker-")
        setWaitForTasksToCompleteOnShutdown(true)
        setAwaitTerminationSeconds(30)
        initialize()
    }
}
