package com.pg.worker.webhook.util

import org.springframework.stereotype.Component
import java.net.InetAddress
import java.net.URI

@Component
class WebhookOutboundUrlValidator {
    fun validate(url: String): URI {
        val uri = runCatching { URI(url) }
            .getOrElse { throw IllegalArgumentException("INVALID_URL") }

        val scheme = uri.scheme?.lowercase() ?: throw IllegalArgumentException("INVALID_URL")
        if (scheme != "https") {
            throw IllegalArgumentException("INVALID_URL_SCHEME")
        }

        val host = uri.host?.lowercase() ?: throw IllegalArgumentException("INVALID_URL")
        if (host == "localhost") {
            throw IllegalArgumentException("BLOCKED_HOST")
        }

        val addresses = runCatching { InetAddress.getAllByName(host).toList() }
            .getOrElse { throw IllegalArgumentException("UNRESOLVABLE_HOST") }

        if (addresses.any { it.isBlockedAddress() }) {
            throw IllegalArgumentException("BLOCKED_HOST")
        }

        return uri
    }

    private fun InetAddress.isBlockedAddress(): Boolean {
        if (isAnyLocalAddress || isLoopbackAddress || isLinkLocalAddress || isSiteLocalAddress || isMulticastAddress) {
            return true
        }
        return hostAddress == "169.254.169.254"
    }
}
