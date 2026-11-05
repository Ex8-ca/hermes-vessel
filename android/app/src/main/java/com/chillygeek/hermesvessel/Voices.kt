package com.chillygeek.hermesvessel

/** A voice Hermes can speak with. The list comes from the paired server; this is the fallback. */
data class Voice(val id: String, val name: String, val note: String, val tone: String)

object Voices {
  const val DEFAULT = "marin"

  val builtIn = listOf(
    Voice("marin", "Marin", "Warm and natural", "female"),
    Voice("shimmer", "Shimmer", "Bright and clear", "female"),
    Voice("coral", "Coral", "Friendly and upbeat", "female"),
    Voice("sage", "Sage", "Calm and unhurried", "female"),
    Voice("alloy", "Alloy", "Even and balanced", "neutral"),
    Voice("cedar", "Cedar", "Warm and natural", "male"),
    Voice("ash", "Ash", "Soft and conversational", "male"),
    Voice("ballad", "Ballad", "Expressive", "male"),
    Voice("echo", "Echo", "Steady", "male"),
    Voice("verse", "Verse", "Lively", "male"),
  )
}
