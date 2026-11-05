package com.chillygeek.hermescompanion

import kotlinx.coroutines.flow.MutableStateFlow

/** Shared UI state between the talk service and the screen. */
object TalkState {
  val status = MutableStateFlow("idle")
  val detail = MutableStateFlow("")
  val running = MutableStateFlow(false)
  val lines = MutableStateFlow<List<Pair<String, String>>>(emptyList())

  fun addLine(role: String, text: String) {
    val clean = text.trim()
    if (clean.isEmpty()) return
    lines.value = (lines.value + (role to clean)).takeLast(6)
  }
}
