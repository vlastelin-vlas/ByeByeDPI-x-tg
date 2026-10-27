package io.github.romanvht.byedpi.services

import java.net.URI

internal data class DotEndpoint(val host: String, val port: Int) {
    companion object {
        fun parse(value: String): DotEndpoint? {
            val address = value.trim()
            if (address.isEmpty() || address.any(Char::isWhitespace) || address.endsWith(':')) return null
            val uri = runCatching { URI("dot://$address") }.getOrNull() ?: return null
            val host = uri.host?.removeSurrounding("[", "]") ?: return null
            if (host.isEmpty() || uri.rawUserInfo != null || !uri.rawPath.isNullOrEmpty() ||
                uri.rawQuery != null || uri.rawFragment != null) return null
            val port = if (uri.port == -1) 853 else uri.port
            return if (port in 1..65535) DotEndpoint(host, port) else null
        }
    }
}
