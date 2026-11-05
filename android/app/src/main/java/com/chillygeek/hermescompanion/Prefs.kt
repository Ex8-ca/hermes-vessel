package com.chillygeek.hermescompanion

import android.content.Context

/** Pairing settings: the server WebSocket URL and the pairing token. */
object Prefs {
  private fun sp(c: Context) = c.getSharedPreferences("companion", Context.MODE_PRIVATE)

  fun url(c: Context): String = sp(c).getString("url", "") ?: ""

  fun token(c: Context): String = sp(c).getString("token", "") ?: ""

  fun save(c: Context, url: String, token: String) {
    sp(c).edit().putString("url", url).putString("token", token).apply()
  }
}
