package com.pg.worker.global.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.ConfigurationCondition
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sqs.SqsClient

@Configuration
@Conditional(SqsConfig.SqsEnabledCondition::class)
class SqsConfig(
    @Value("\${aws.region}") private val region: String,
) {
    class SqsEnabledCondition : AnyNestedCondition(ConfigurationCondition.ConfigurationPhase.REGISTER_BEAN) {
        @ConditionalOnProperty(prefix = "webhook.sqs", name = ["enabled"], havingValue = "true")
        class WebhookSqsEnabled

        @ConditionalOnProperty(prefix = "settlement.sqs", name = ["enabled"], havingValue = "true")
        class SettlementSqsEnabled
    }

    @Bean
    fun sqsClient(): SqsClient {
        return SqsClient.builder()
            .region(Region.of(region))
            .build()
    }
}
