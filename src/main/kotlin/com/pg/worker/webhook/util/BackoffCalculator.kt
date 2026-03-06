package com.pg.worker.webhook.util

import java.time.LocalDateTime
import kotlin.random.Random

object BackoffCalculator {
    private val baseDelaysMs = listOf(
        5_000L,
        30_000L,
        120_000L,
        600_000L,
        1_800_000L,
        7_200_000L,
    )
    private const val JITTER_FACTOR = 0.2

    fun nextDelayMs(attemptNo: Int): Long {
        val base = baseDelaysMs.getOrElse(attemptNo - 1) { baseDelaysMs.last() }
        val jitter = (base * JITTER_FACTOR * Random.nextDouble()).toLong()
        return base + jitter
    }

    fun nextAttemptAt(attemptNo: Int, from: LocalDateTime = LocalDateTime.now()): LocalDateTime {
        return from.plusNanos(nextDelayMs(attemptNo) * 1_000_000L)
    }
}
