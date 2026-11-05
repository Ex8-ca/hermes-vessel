package com.chillygeek.hermesvessel

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A paired Hermes gateway: its Vessel WebSocket URL and pairing token. The URL is its identity. */
data class Gateway(val url: String, val token: String) {
  /** ai5080.tailnet.ts.net/vessel */
  val path: String get() = Pairing.displayPath(url)

  /** ai5080 (or "ai5080 · work" for a gateway on another path of the same machine) */
  val name: String get() = Pairing.shortName(url)
}

/** On-phone settings: the paired gateways (one is active) and the voice choice. */
object Prefs {
  private fun sp(c: Context) = c.getSharedPreferences("vessel", Context.MODE_PRIVATE)

  /** Every paired gateway, in the order they were added. */
  fun gateways(c: Context): List<Gateway> {
    migrate(c)
    return read(c)
  }

  /** The gateway calls go to, or null when nothing is paired. */
  fun active(c: Context): Gateway? {
    val list = gateways(c)
    val activeUrl = sp(c).getString("active", "") ?: ""
    return list.firstOrNull { it.url == activeUrl } ?: list.firstOrNull()
  }

  /** The active gateway's address and token ("" when unpaired). */
  fun url(c: Context): String = active(c)?.url ?: ""

  fun token(c: Context): String = active(c)?.token ?: ""

  /** Pair a gateway, or refresh the token of one already paired. New pairings become active unless told otherwise. */
  fun save(c: Context, url: String, token: String, makeActive: Boolean = true) {
    val list = gateways(c).toMutableList()
    val at = list.indexOfFirst { it.url == url }
    if (at >= 0) list[at] = Gateway(url, token) else list += Gateway(url, token)
    write(c, list, if (makeActive) url else active(c)?.url ?: url)
  }

  fun setActive(c: Context, url: String) {
    val list = gateways(c)
    if (list.any { it.url == url }) write(c, list, url)
  }

  /** Forget one gateway. If it was the active one, the first remaining gateway takes over. */
  fun remove(c: Context, url: String) {
    ScreenRequests.setAllowed(c, url, false)
    val current = active(c)?.url
    val list = gateways(c).filterNot { it.url == url }
    write(c, list, if (current == url) list.firstOrNull()?.url else current)
  }

  /** The chosen voice id, or "" for the gateway's default. Shared by all gateways. */
  fun voice(c: Context): String = sp(c).getString("voice", "") ?: ""

  fun saveVoice(c: Context, voice: String) {
    sp(c).edit().putString("voice", voice).apply()
  }

  private fun read(c: Context): List<Gateway> {
    val raw = sp(c).getString("gateways", null) ?: return emptyList()
    return try {
      val array = JSONArray(raw)
      (0 until array.length()).mapNotNull { i ->
        val o = array.optJSONObject(i) ?: return@mapNotNull null
        val url = o.optString("url")
        val token = o.optString("token")
        if (url.isBlank() || token.isBlank()) null else Gateway(url, token)
      }
    } catch (e: Exception) {
      emptyList()
    }
  }

  private fun write(c: Context, list: List<Gateway>, activeUrl: String?) {
    val array = JSONArray()
    for (g in list) array.put(JSONObject().put("url", g.url).put("token", g.token))
    sp(c).edit().putString("gateways", array.toString()).putString("active", activeUrl ?: "").apply()
  }

  /** Builds up to 0.1.0-rc4 stored a single "url" + "token"; carry that pairing over as the active gateway. */
  private fun migrate(c: Context) {
    val p = sp(c)
    if (!p.contains("url") && !p.contains("token")) return
    val url = p.getString("url", "") ?: ""
    val token = p.getString("token", "") ?: ""
    p.edit().remove("url").remove("token").apply()
    if (url.isBlank() || token.isBlank()) return
    val list = read(c).toMutableList()
    if (list.none { it.url == url }) list += Gateway(url, token)
    write(c, list, url)
  }
}
