package com.pg.worker.webhook.util

import com.pg.worker.webhook.application.EndpointConcurrencyLimiter
import com.gop.logging.contract.StructuredLogger
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(prefix = "webhook.limiter", name = ["type"], havingValue = "redis")
class RedisEndpointLimiter(
    private val redisTemplate: StringRedisTemplate,
    @Value("\${webhook.limiter.redis.max-permits}") private val maxPermits: Int,
    @Value("\${webhook.limiter.redis.permit-ttl-ms}") private val permitTtlMs: Long,
    @Value("\${webhook.limiter.redis.fail-open}") private val failOpen: Boolean,
    private val log: StructuredLogger) : EndpointConcurrencyLimiter {

    override fun tryAcquire(endpointId: Long): Boolean {
        return try {
            val result = redisTemplate.execute(
                acquireScript,
                listOf(key(endpointId)),
                maxPermits.toString(),
                permitTtlMs.toString(),
            )
            result == 1L
        } catch (e: Exception) {
            if (failOpen) {
                log.warn("[RedisEndpointLimiter] acquire failed (fail-open): endpointId={}", endpointId, e)
                true
            } else {
                log.error("[RedisEndpointLimiter] acquire failed: endpointId={}", endpointId, e)
                false
            }
        }
    }

    override fun release(endpointId: Long) {
        try {
            redisTemplate.execute(releaseScript, listOf(key(endpointId)))
        } catch (e: Exception) {
            log.error("[RedisEndpointLimiter] release failed: endpointId={}", endpointId, e)
        }
    }

    private fun key(endpointId: Long): String = "wh:ep:$endpointId:permits"

    companion object {
        private val acquireScript = DefaultRedisScript<Long>().apply {
            setResultType(Long::class.java)
            setScriptText(
                """
                local key = KEYS[1]
                local max_permits = tonumber(ARGV[1])
                local ttl_ms = tonumber(ARGV[2])

                local current = tonumber(redis.call('INCR', key))
                if current > max_permits then
                  redis.call('DECR', key)
                  return 0
                end

                redis.call('PEXPIRE', key, ttl_ms)
                return 1
                """.trimIndent()
            )
        }

        private val releaseScript = DefaultRedisScript<Long>().apply {
            setResultType(Long::class.java)
            setScriptText(
                """
                local key = KEYS[1]
                local current = tonumber(redis.call('GET', key) or '0')
                if current <= 0 then
                  return 0
                end

                current = tonumber(redis.call('DECR', key))
                if current <= 0 then
                  redis.call('DEL', key)
                end
                return 1
                """.trimIndent()
            )
        }
    }
}
