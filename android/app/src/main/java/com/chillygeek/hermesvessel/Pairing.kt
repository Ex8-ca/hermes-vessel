package com.chillygeek.hermesvessel

import android.net.Uri

/** Turns a pairing link into the server address and token. */
object Pairing {
  data class Link(val wsUrl: String, val token: String)

  /**
   * Accepts the deep link (hermesvessel://pair?url=wss://...&t=...) or the pairing page
   * address (https://host/vessel/?t=...), which people can copy and paste.
   */
  fun parse(input: String): Link? {
    val uri = try {
      Uri.parse(input.trim())
    } catch (e: Exception) {
      return null
    }
    val token = uri.getQueryParameter("t")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return when (uri.scheme?.lowercase()) {
      "hermesvessel" -> uri.getQueryParameter("url")
        ?.takeIf { it.startsWith("wss://") || it.startsWith("ws://") }
        ?.let { Link(it, token) }
      "https", "http" -> {
        val host = uri.encodedAuthority?.takeIf { it.isNotEmpty() } ?: return null
        val raw = uri.encodedPath?.takeIf { it.isNotEmpty() } ?: "/"
        val dir = when {
          raw.endsWith("/") -> raw
          raw.substringAfterLast('/').contains('.') -> raw.substringBeforeLast('/') + "/"
          else -> "$raw/"
        }
        val scheme = if (uri.scheme.equals("https", ignoreCase = true)) "wss" else "ws"
        Link("$scheme://$host${dir}ws", token)
      }
      else -> null
    }
  }

  /** wss://host/vessel/ws -> https://host/vessel/health */
  fun healthUrl(wsUrl: String): String? {
    if (!wsUrl.endsWith("/ws")) return null
    return wsUrl.removeSuffix("/ws")
      .replaceFirst("wss://", "https://")
      .replaceFirst("ws://", "http://") + "/health"
  }

  /** ai5080.tailnet.ts.net -> ai5080 */
  fun hostLabel(wsUrl: String): String =
    Uri.parse(wsUrl).host?.substringBefore('.')?.takeIf { it.isNotEmpty() } ?: "Hermes"
}
