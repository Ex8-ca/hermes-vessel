package com.chillygeek.hermesvessel

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The light in the vessel: a glass bead with light moving inside it.
 * It breathes when idle, swells with your voice (jade) and Hermes's voice (gold),
 * and grows orbiting sparks while Hermes is working (violet).
 */
@Composable
fun Orb(state: String, level: Float, modifier: Modifier = Modifier) {
  val palette = OrbPalette.of(state)
  val primary by animateColorAsState(palette.primary, tween(700), label = "orbPrimary")
  val deep by animateColorAsState(palette.deep, tween(700), label = "orbDeep")
  val energy by animateFloatAsState(palette.energy, tween(800), label = "orbEnergy")
  val lvl by animateFloatAsState(level, tween(110), label = "orbLevel")
  val infinite = rememberInfiniteTransition(label = "orb")
  val phase by infinite.animateFloat(
    0f, (2 * PI).toFloat(), infiniteRepeatable(tween(14000, easing = LinearEasing)), label = "phase",
  )
  val breath by infinite.animateFloat(
    0f, 1f, infiniteRepeatable(tween(4200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breath",
  )
  val spin by infinite.animateFloat(
    0f, 360f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "spin",
  )
  val calm = state == "idle" || state == "muted"
  val speed = when (state) {
    "thinking" -> 3.2f
    "speaking", "listening" -> 1.7f
    else -> 1f
  }

  fun radius(minDim: Float): Float =
    minDim * 0.33f * (0.94f + (if (calm) 0.06f else 0.025f) * breath + 0.20f * lvl)

  Box(modifier) {
    // Halo and accents: drawn on the normal layer so they can spill past the bounds.
    Canvas(Modifier.matchParentSize()) {
      val c = center
      val r = radius(size.minDimension)
      drawCircle(
        Brush.radialGradient(
          0f to primary.copy(alpha = 0.34f * energy),
          0.45f to primary.copy(alpha = 0.10f * energy),
          1f to Color.Transparent,
          center = c,
          radius = r * 2.1f,
        ),
        radius = r * 2.1f,
        center = c,
      )
      // Bloom hugging the edge, so the light spills instead of ending at a hard rim.
      drawCircle(
        Brush.radialGradient(
          0.70f to primary.copy(alpha = 0.55f * energy),
          1f to Color.Transparent,
          center = c,
          radius = r * 1.32f,
        ),
        radius = r * 1.32f,
        center = c,
      )
      // The vessel: a faint glass ring the light lives in.
      drawCircle(Ivory.copy(alpha = 0.09f), r * 1.24f, c, style = Stroke(width = 1.dp.toPx()))
      if (state == "listening" || state == "speaking") {
        drawCircle(primary.copy(alpha = 0.26f * lvl), r * (1.12f + 0.40f * lvl), c, style = Stroke(width = 1.5.dp.toPx()))
      }
      if (state == "thinking") {
        for (k in 0 until 3) {
          val ang = Math.toRadians((spin + k * 120f).toDouble())
          val pos = Offset(c.x + cos(ang).toFloat() * r * 1.42f, c.y + sin(ang).toFloat() * r * 1.42f)
          drawCircle(primary.copy(alpha = 0.22f), r * 0.10f, pos)
          drawCircle(lerp(primary, Color.White, 0.5f), r * 0.04f, pos)
        }
      }
      if (state == "connecting") {
        val d = r * 2.7f
        drawArc(
          color = primary.copy(alpha = 0.85f),
          startAngle = spin,
          sweepAngle = 80f,
          useCenter = false,
          topLeft = Offset(c.x - d / 2f, c.y - d / 2f),
          size = Size(d, d),
          style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
        )
      }
    }
    // The glass body and the light inside it, composited offscreen so the light adds up.
    Canvas(Modifier.matchParentSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
      val c = center
      val r = radius(size.minDimension)
      val lightPos = Offset(c.x - r * 0.16f, c.y - r * 0.20f)
      // Brightest inside, glowing (not darkening) toward the edge.
      drawCircle(
        Brush.radialGradient(
          0f to lerp(primary, Color.White, 0.62f),
          0.40f to lerp(primary, Color.White, 0.22f),
          0.78f to primary,
          0.94f to lerp(primary, deep, 0.35f),
          1f to lerp(primary, deep, 0.15f),
          center = lightPos,
          radius = r * 1.18f,
        ),
        radius = r,
        center = c,
      )
      val sphere = Path().apply { addOval(Rect(c, r)) }
      clipPath(sphere) {
        for (i in 0 until 3) {
          val a = phase * speed * (1f + i * 0.41f) + i * 2.09f
          val off = r * (0.30f + 0.12f * lvl)
          val pos = Offset(c.x + cos(a) * off, c.y + sin(a * 1.17f) * off * 0.8f)
          val col = if (i == 1) Color.White else lerp(primary, Color.White, 0.25f)
          drawCircle(
            Brush.radialGradient(
              0f to col.copy(alpha = (0.32f * energy + 0.20f * lvl).coerceAtMost(0.65f)),
              1f to Color.Transparent,
              center = pos,
              radius = r * 0.75f,
            ),
            radius = r * 0.75f,
            center = pos,
            blendMode = BlendMode.Plus,
          )
        }
      }
      // A soft glint, not a plastic highlight.
      val glint = Offset(c.x - r * 0.30f, c.y - r * 0.36f)
      drawCircle(
        Brush.radialGradient(0f to Color.White.copy(alpha = 0.30f), 1f to Color.Transparent, center = glint, radius = r * 0.30f),
        radius = r * 0.30f,
        center = glint,
      )
      drawCircle(color = Color.White.copy(alpha = 0.07f), radius = r, center = c, style = Stroke(width = 1.dp.toPx()))
    }
  }
}
