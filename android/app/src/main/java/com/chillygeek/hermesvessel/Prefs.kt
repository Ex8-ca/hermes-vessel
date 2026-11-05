package com.chillygeek.hermesvessel

import android.content.Context

/** Pairing settings: the server WebSocket URL and the pairing token. */
object Prefs {
  private fun sp(c: Context) = c.getSharedPreferences("vessel", Context.MODE_PRIVATE)

  fun url(c: Context): String = sp(c).getString("url", "") ?: ""

  fun token(c: Context): String = sp(c).getString("token", "") ?: ""

  fun save(c: Context, url: String, token: String) {
    sp(c).edit().putString("url", url).putString("token", token).apply()
  }

  /** The chosen voice id, or "" for the server's default. Kept across re-pairing. */
  fun voice(c: Context): String = sp(c).getString("voice", "") ?: ""

  fun saveVoice(c: Context, voice: String) {
    sp(c).edit().putString("voice", voice).apply()
  }

  /** Forget the pairing (keeps the voice choice). */
  fun clear(c: Context) {
    sp(c).edit().remove("url").remove("token").apply()
  }
}
