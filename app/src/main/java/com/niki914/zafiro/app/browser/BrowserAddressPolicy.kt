package com.niki914.zafiro.app.browser

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/** URL admission, not a network firewall. Private DNS resolution requires a separate transport policy. */
internal object BrowserAddressPolicy {
    fun normalize(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() && it.length <= 8192 } ?: return null
        val uri = try { URI(value) } catch (_: URISyntaxException) { return null }
        if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("https", "http") || uri.rawUserInfo != null) return null
        val host = uri.host?.lowercase(Locale.ROOT)?.trim('[', ']')?.trimEnd('.') ?: return null
        if (host.isBlank() || host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) return null
        if (host.contains(':')) return null
        if (host.split('.').all { it.matches(Regex("(?i)(0x[0-9a-f]+|[0-9]+)")) } && host.any { it.isLetter() }) return null
        if (host.all { it.isDigit() || it == '.' }) {
            val parts = host.split('.').map { it.toIntOrNull() ?: return null }
            if (parts.size != 4 || parts.any { it !in 0..255 }) return null
            val a = parts[0]; val b = parts[1]
            if (a in setOf(0, 10, 127) || a >= 224 || (a == 169 && b == 254) ||
                (a == 172 && b in 16..31) || (a == 192 && b == 168)) return null
        }
        if (uri.port != -1 && uri.port !in 1..65535) return null
        return uri.toASCIIString()
    }
}
