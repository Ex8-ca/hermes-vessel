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

  /** wss://host/vessel/ws -> https://host/vessel */
  private fun httpBase(wsUrl: String): String? {
    if (!wsUrl.endsWith("/ws")) return null
    return wsUrl.removeSuffix("/ws").replaceFirst("wss://", "https://").replaceFirst("ws://", "http://")
  }

  fun healthUrl(wsUrl: String): String? = httpBase(wsUrl)?.let { "$it/health" }

  fun voicesUrl(wsUrl: String, token: String): String? =
    httpBase(wsUrl)?.let { "$it/voices?t=" + Uri.encode(token) }

  fun sampleUrl(wsUrl: String, token: String, voice: String): String? =
    httpBase(wsUrl)?.let { "$it/voices/" + Uri.encode(voice) + "/sample?t=" + Uri.encode(token) }

  /**
   * The gateway's full address as people read it:
   * wss://ai5080.tailnet.ts.net/vessel/ws -> ai5080.tailnet.ts.net/vessel.
   * Unencrypted gateways keep their scheme (http://192.168.1.3:8796) so that is visible.
   */
  fun displayPath(wsUrl: String): String {
    val base = wsUrl.removeSuffix("/ws").removeSuffix("/")
    return when {
      base.startsWith("wss://") -> base.removePrefix("wss://")
      base.startsWith("ws://") -> "http://" + base.removePrefix("ws://")
      else -> base
    }
  }

  /**
   * A short name for the header chip: the machine name (ai5080), plus the path when it isn't the
   * default /vessel, so two gateways on one machine stay distinguishable (ai5080 · work).
   */
  fun shortName(wsUrl: String): String {
    val uri = Uri.parse(wsUrl)
    val host = uri.host?.takeIf { it.isNotEmpty() } ?: return "Hermes"
    val isAddress = host.contains(':') || host.all { it.isDigit() || it == '.' }
    val machine = if (isAddress) host else host.substringBefore('.')
    val dir = (uri.path ?: "").removeSuffix("/ws").trim('/')
    val last = dir.substringAfterLast('/')
    return if (last.isEmpty() || last == "vessel") machine else "$machine · $last"
  }
}
