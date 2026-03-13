package com.pg.worker.webhook.util

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class WebhookOutboundUrlValidatorTest {

    private val validator = WebhookOutboundUrlValidator()

    @Test
    fun `https public ip는 허용된다`() {
        assertDoesNotThrow {
            validator.validate("https://8.8.8.8/webhook")
        }
    }

    @Test
    fun `http 스킴은 차단된다`() {
        assertThrows(IllegalArgumentException::class.java) {
            validator.validate("http://8.8.8.8/webhook")
        }
    }

    @Test
    fun `localhost는 차단된다`() {
        assertThrows(IllegalArgumentException::class.java) {
            validator.validate("https://localhost/webhook")
        }
    }

    @Test
    fun `private ip는 차단된다`() {
        assertThrows(IllegalArgumentException::class.java) {
            validator.validate("https://192.168.0.10/webhook")
        }
    }

    @Test
    fun `10 대역 private ip는 차단된다`() {
        assertThrows(IllegalArgumentException::class.java) {
            validator.validate("https://10.0.0.1/webhook")
        }
    }

    @Test
    fun `172 16부터 31 대역 private ip는 차단된다`() {
        assertThrows(IllegalArgumentException::class.java) {
            validator.validate("https://172.16.0.1/webhook")
        }
    }

    @Test
    fun `cgnat 대역 ip는 차단된다`() {
        assertThrows(IllegalArgumentException::class.java) {
            validator.validate("https://100.64.0.1/webhook")
        }
    }

    @Test
    fun `metadata ip는 차단된다`() {
        assertThrows(IllegalArgumentException::class.java) {
            validator.validate("https://169.254.169.254/latest/meta-data")
        }
    }

    @Test
    fun `ipv6 loopback은 차단된다`() {
        assertThrows(IllegalArgumentException::class.java) {
            validator.validate("https://[::1]/webhook")
        }
    }

    @Test
    fun `빈 문자열 URL은 차단된다`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            validator.validate("")
        }
        assertEquals("INVALID_URL", exception.message)
    }

    @Test
    fun `443 이외 포트는 차단된다`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            validator.validate("https://8.8.8.8:444/webhook")
        }
        assertEquals("BLOCKED_PORT", exception.message)
    }

    @Test
    fun `userinfo 포함 URL은 차단된다`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            validator.validate("https://user:pass@8.8.8.8/webhook")
        }
        assertEquals("USERINFO_NOT_ALLOWED", exception.message)
    }

    @Test
    fun `fragment 포함 URL은 차단된다`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            validator.validate("https://8.8.8.8/webhook#frag")
        }
        assertEquals("FRAGMENT_NOT_ALLOWED", exception.message)
    }

    @Test
    fun `명시적 443 포트는 허용된다`() {
        assertDoesNotThrow {
            validator.validate("https://8.8.8.8:443/webhook")
        }
    }
}
