package com.chillygeek.hermesvessel

import kotlinx.coroutines.flow.MutableStateFlow

/** Shared state between the talk service and the screen. */
/** What the last check of a gateway found. */
enum class GatewayStatus {
  CHECKING, ONLINE, OFFLINE, REJECTED;

  /** For the header chip: reachable and accepting this phone, not, or unknown yet. */
  val online: Boolean?
    get() = when (this) {
      ONLINE -> true
      OFFLINE, REJECTED -> false
      CHECKING -> null
    }
}

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
  /** Vessel's own screen is showing (the call button hides then). */
  val appVisible = MutableStateFlow(false)
  /** The floating call button is switched on in Settings. */
  val bubbleOn = MutableStateFlow(false)
  /** "Display over other apps" is granted (re-checked whenever Vessel comes back on screen). */
  val overlayGranted = MutableStateFlow(false)
  /** null = not checked yet; true/false = the paired Hermes answered /health. */
  val hostOnline = MutableStateFlow<Boolean?>(null)

  // Gateways (Settings): every paired Hermes, which one calls go to, and whether each answers.
  val gateways = MutableStateFlow<List<Gateway>>(emptyList())
  val activeGateway = MutableStateFlow<String?>(null)
  val gatewayStatus = MutableStateFlow<Map<String, GatewayStatus>>(emptyMap())
  /** "Call ended · 2:31", shown briefly after hanging up. */
  val lastCall = MutableStateFlow("")

  // Settings
  val voices = MutableStateFlow(Voices.builtIn)
  val selectedVoice = MutableStateFlow(Voices.DEFAULT)
  /** The voice whose sample is playing (or loading), if any. */
  val previewing = MutableStateFlow<String?>(null)
  val previewLoading = MutableStateFlow(false)
  val previewError = MutableStateFlow("")

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
