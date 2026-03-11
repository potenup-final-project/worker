package com.pg.worker.webhook.reconciliation.scheduler

import com.pg.worker.webhook.reconciliation.application.WebhookReconciliationService
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDate

@Component
@ConditionalOnProperty(prefix = "webhook.recon", name = ["enabled"], havingValue = "true")
class WebhookReconciliationJob(
    private val reconciliationService: WebhookReconciliationService,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 0 4 * * *", scheduler = "webhookWorkerScheduler")
    fun runDailyWebhookReconciliation() {
        val yesterday = LocalDate.now().minusDays(1)
        val start = System.currentTimeMillis()
        log.info("[WebhookReconciliationJob] started targetDate={}", yesterday)

        val steps = listOf(
            ReconciliationStep("detectStaleDeliveries") { reconciliationService.detectStaleDeliveries(yesterday) },
            ReconciliationStep("detectDegradedEndpoints") { reconciliationService.detectDegradedEndpoints(yesterday) },
            ReconciliationStep("detectMissingDeliveries") { reconciliationService.detectMissingDeliveries(yesterday) },
            ReconciliationStep("resolveOpenMismatches") { reconciliationService.resolveOpenMismatches() },
        )

        steps.forEach { step ->
            runCatching { step.execute() }
                .onFailure { e ->
                    meterRegistry.counter("webhook.recon.job.error", "step", step.name).increment()
                    log.error("[WebhookReconciliationJob] step failed step={}", step.name, e)
                }
        }

        val elapsed = System.currentTimeMillis() - start
        meterRegistry.timer("webhook.recon.job.duration").record(elapsed, java.util.concurrent.TimeUnit.MILLISECONDS)
        log.info("[WebhookReconciliationJob] finished targetDate={} elapsedMs={}", yesterday, elapsed)
    }

    private data class ReconciliationStep(
        val name: String,
        val execute: () -> Unit,
    )
}
