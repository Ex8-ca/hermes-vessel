package com.chillygeek.hermesvessel

import kotlinx.coroutines.flow.MutableStateFlow

/** Shared state between the talk service and the screen. */
object TalkState {
  /** Server-reported call state: idle | connecting | listening | thinking | speaking | error. */
  val status = MutableStateFlow("idle")
  /** What Hermes is working on (while thinking) or what went wrong (on error). */
  val detail = MutableStateFlow("")
  /** Where the audio goes, e.g. "Through Pixel Buds Pro". */
  val route = MutableStateFlow("")
  val running = MutableStateFlow(false)
  val muted = MutableStateFlow(false)
  /** True while Hermes's voice is actually coming out of the speaker. */
  val playing = MutableStateFlow(false)
  /** 0..1 loudness of your voice and of Hermes's voice, about ten updates a second. */
  val micLevel = MutableStateFlow(0f)
  val outLevel = MutableStateFlow(0f)
  /** When Hermes started on a hand-off (for the elapsed timer), or 0. */
  val thinkingSince = MutableStateFlow(0L)
  /** Hermes's words as they are spoken. */
  val liveCaption = MutableStateFlow("")
  val lines = MutableStateFlow<List<Pair<String, String>>>(emptyList())
  /** null = not checked yet; true/false = the paired Hermes answered /health. */
  val hostOnline = MutableStateFlow<Boolean?>(null)
  /** "Call ended · 2:31", shown briefly after hanging up. */
  val lastCall = MutableStateFlow("")

  fun addLine(role: String, text: String) {
    val clean = text.trim()
    if (clean.isEmpty()) return
    lines.value = (lines.value + (role to clean)).takeLast(6)
  }

  fun resetCall() {
    lines.value = emptyList()
    liveCaption.value = ""
    detail.value = ""
    micLevel.value = 0f
    outLevel.value = 0f
    playing.value = false
    thinkingSince.value = 0L
    muted.value = false
    lastCall.value = ""
  }
}
