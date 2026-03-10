package com.pg.worker.webhook.util

import org.springframework.stereotype.Component
import java.net.IDN
import java.net.InetAddress
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.URI

@Component
class WebhookOutboundUrlValidator {
    fun validate(url: String): URI {
        val uri = runCatching { URI(url) }
            .getOrElse { throw IllegalArgumentException(ERROR_INVALID_URL) }

        if (uri.isOpaque) {
            throw IllegalArgumentException(ERROR_INVALID_URL)
        }

        if (!uri.userInfo.isNullOrBlank()) {
            throw IllegalArgumentException(ERROR_USERINFO_NOT_ALLOWED)
        }

        if (!uri.fragment.isNullOrBlank()) {
            throw IllegalArgumentException(ERROR_FRAGMENT_NOT_ALLOWED)
        }

        val scheme = uri.scheme?.lowercase() ?: throw IllegalArgumentException(ERROR_INVALID_URL)
        if (scheme != "https") {
            throw IllegalArgumentException(ERROR_INVALID_URL_SCHEME)
        }

        val normalizedHost = normalizeHost(uri.host)
        if (normalizedHost == "localhost") {
            throw IllegalArgumentException(ERROR_BLOCKED_HOST)
        }

        val normalizedUri = URI(
            uri.scheme,
            null,
            normalizedHost,
            normalizePort(uri.port),
            uri.path,
            uri.query,
            null,
        )

        val addresses = resolveAddresses(normalizedHost)

        if (addresses.any { it.isBlockedAddress() }) {
            throw IllegalArgumentException(ERROR_BLOCKED_HOST)
        }

        return normalizedUri
    }

    private fun normalizeHost(host: String?): String {
        val rawHost = host?.trim()?.trimEnd('.')?.lowercase()
            ?: throw IllegalArgumentException(ERROR_INVALID_URL)
        val asciiHost = runCatching { IDN.toASCII(rawHost, IDN.USE_STD3_ASCII_RULES) }
            .getOrElse { throw IllegalArgumentException(ERROR_INVALID_HOST) }
        if (asciiHost.isBlank()) {
            throw IllegalArgumentException(ERROR_INVALID_HOST)
        }
        return asciiHost
    }

    private fun normalizePort(port: Int): Int {
        return when (port) {
            -1, 443 -> -1
            else -> throw IllegalArgumentException(ERROR_BLOCKED_PORT)
        }
    }

    private fun resolveAddresses(host: String): List<InetAddress> {
        return runCatching { InetAddress.getAllByName(host).toList() }
            .getOrElse { throw IllegalArgumentException(ERROR_UNRESOLVABLE_HOST) }
    }

    private fun InetAddress.isBlockedAddress(): Boolean {
        if (isAnyLocalAddress || isLoopbackAddress || isLinkLocalAddress || isSiteLocalAddress || isMulticastAddress) {
            return true
        }

        return when (this) {
            is Inet4Address -> isBlockedIpv4(this)
            is Inet6Address -> isBlockedIpv6(this)
            else -> false
        }
    }

    private fun isBlockedIpv4(address: Inet4Address): Boolean {
        val bytes = address.address
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF

        if (b0 == 10) return true
        if (b0 == 127) return true
        if (b0 == 192 && b1 == 168) return true
        if (b0 == 172 && b1 in 16..31) return true
        if (b0 == 169 && b1 == 254) return true
        if (b0 == 100 && b1 in 64..127) return true
        if (address.hostAddress == "169.254.169.254") return true

        return false
    }

    private fun isBlockedIpv6(address: Inet6Address): Boolean {
        val bytes = address.address
        val first = bytes[0].toInt() and 0xFF
        if ((first and 0xFE) == 0xFC) {
            return true // unique local fc00::/7
        }

        val host = address.hostAddress.lowercase()
        if (host == "::" || host == "0:0:0:0:0:0:0:0") return true
        if (host == "fd00:ec2::254") return true

        return false
    }

    companion object {
        private const val ERROR_INVALID_URL = "INVALID_URL"
        private const val ERROR_INVALID_URL_SCHEME = "INVALID_URL_SCHEME"
        private const val ERROR_INVALID_HOST = "INVALID_HOST"
        private const val ERROR_USERINFO_NOT_ALLOWED = "USERINFO_NOT_ALLOWED"
        private const val ERROR_FRAGMENT_NOT_ALLOWED = "FRAGMENT_NOT_ALLOWED"
        private const val ERROR_BLOCKED_PORT = "BLOCKED_PORT"
        private const val ERROR_UNRESOLVABLE_HOST = "UNRESOLVABLE_HOST"
        private const val ERROR_BLOCKED_HOST = "BLOCKED_HOST"
    }
}
