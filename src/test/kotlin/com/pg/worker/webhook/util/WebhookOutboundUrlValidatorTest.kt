package com.pg.worker.webhook.util

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
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
    fun `metadata ip는 차단된다`() {
        assertThrows(IllegalArgumentException::class.java) {
            validator.validate("https://169.254.169.254/latest/meta-data")
        }
    }
}
