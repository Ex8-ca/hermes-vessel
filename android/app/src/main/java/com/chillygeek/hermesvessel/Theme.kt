package com.chillygeek.hermesvessel

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// "A light in a vessel": warm ink, ivory type, and a light that changes colour with what Hermes is doing.
val Ink = Color(0xFF0D0B09)
val InkTop = Color(0xFF18130F)
val Ivory = Color(0xFFF1EADF)
val Muted = Color(0xFF9C9388)
val Gold = Color(0xFFE9AE5B)
val GoldLight = Color(0xFFF6CD84)
val Jade = Color(0xFF6FD3B4)
val Coral = Color(0xFFF07A6E)
val EndRed = Color(0xFFE5484D)
val Panel = Color(0xFF1A1511)

/** Orb colours per state: the light itself, its deep edge, and how brightly it glows. */
data class OrbPalette(val primary: Color, val deep: Color, val energy: Float) {
  companion object {
    fun of(state: String): OrbPalette = when (state) {
      "connecting" -> OrbPalette(Color(0xFFEFE6D6), Color(0xFF8C7D66), 0.7f)
      "listening" -> OrbPalette(Jade, Color(0xFF1F6F69), 1f)
      "muted" -> OrbPalette(Color(0xFF8A8279), Color(0xFF3E3832), 0.25f)
      "thinking" -> OrbPalette(Color(0xFF9DB2FF), Color(0xFF34408F), 0.9f)
      "speaking" -> OrbPalette(Color(0xFFF4C46C), Color(0xFFB4592A), 1f)
      "error" -> OrbPalette(Coral, Color(0xFF8E2F2F), 0.6f)
      else -> OrbPalette(Gold, Color(0xFF8A4520), 0.45f) // idle: an ember, asleep but present
    }
  }
}

@OptIn(ExperimentalTextApi::class)
private fun fraunces(weight: Int, opsz: Float) = Font(
  R.font.fraunces,
  weight = FontWeight(weight),
  variationSettings = FontVariation.Settings(
    FontVariation.weight(weight),
    FontVariation.Setting("opsz", opsz),
    FontVariation.Setting("SOFT", 100f),
    FontVariation.Setting("WONK", 0f),
  ),
)

/** Fraunces, a soft serif: Hermes's own voice on screen. */
val Fraunces = FontFamily(fraunces(300, 24f), fraunces(400, 72f), fraunces(500, 36f))

object Type {
  val wordmark = TextStyle(fontFamily = Fraunces, fontWeight = FontWeight(500), fontSize = 22.sp, letterSpacing = 0.2.sp, color = Ivory)
  val display = TextStyle(fontFamily = Fraunces, fontWeight = FontWeight(400), fontSize = 40.sp, lineHeight = 46.sp, letterSpacing = (-0.6).sp, color = Ivory)
  val headline = TextStyle(fontFamily = Fraunces, fontWeight = FontWeight(400), fontSize = 32.sp, lineHeight = 38.sp, letterSpacing = (-0.4).sp, color = Ivory)
  val hermes = TextStyle(fontFamily = Fraunces, fontWeight = FontWeight(300), fontSize = 20.sp, lineHeight = 28.sp, color = Ivory)
  val body = TextStyle(fontSize = 16.sp, lineHeight = 23.sp, color = Muted)
  val user = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, color = Ivory.copy(alpha = 0.62f))
  val overline = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 1.8.sp, fontWeight = FontWeight.Medium, color = Muted)
  val label = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, color = Muted)
  val button = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp)
  val code = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = GoldLight)
}

@Composable
fun VesselTheme(content: @Composable () -> Unit) {
  MaterialTheme(
    colorScheme = darkColorScheme(
      primary = Gold,
      onPrimary = Ink,
      background = Ink,
      onBackground = Ivory,
      surface = Panel,
      onSurface = Ivory,
      surfaceVariant = Color(0xFF231C16),
      onSurfaceVariant = Muted,
      error = Coral,
      outline = Color(0x33F1EADF),
    ),
    content = content,
  )
}
