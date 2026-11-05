package com.chillygeek.hermesvessel

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
  private val permissions =
    registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
      if (hasMic()) {
        TalkService.start(this)
      } else {
        TalkState.status.value = "error"
        TalkState.detail.value = "Hermes needs the microphone to hear you."
      }
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    handlePairing(intent)
    setContent { VesselScreen(onTap = { toggle() }) }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    handlePairing(intent)
  }

  private fun hasMic() =
    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

  private fun handlePairing(intent: Intent?) {
    val data: Uri = intent?.data ?: return
    if (data.scheme != "hermesvessel" || data.host != "pair") return
    val url = data.getQueryParameter("url")
    val token = data.getQueryParameter("t")
    if (url.isNullOrBlank() || token.isNullOrBlank()) return
    Prefs.save(this, url, token)
    TalkState.status.value = "idle"
    TalkState.detail.value = "Paired with " + (Uri.parse(url).host ?: "your Hermes")
  }

  private fun toggle() {
    if (TalkState.running.value) {
      TalkService.stop(this)
      return
    }
    if (Prefs.token(this).isBlank()) {
      TalkState.status.value = "error"
      TalkState.detail.value = "Not paired yet. Open the pairing link on this phone."
      return
    }
    val wanted = mutableListOf(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
    if (Build.VERSION.SDK_INT >= 31) wanted += Manifest.permission.BLUETOOTH_CONNECT
    val missing = wanted.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
    if (missing.isEmpty()) TalkService.start(this) else permissions.launch(missing.toTypedArray())
  }
}

@Composable
fun VesselScreen(onTap: () -> Unit) {
  val status by TalkState.status.collectAsState()
  val detail by TalkState.detail.collectAsState()
  val running by TalkState.running.collectAsState()
  val lines by TalkState.lines.collectAsState()
  val color = when (status) {
    "listening" -> Color(0xFF2FBF8A)
    "speaking" -> Color(0xFF5B7CFF)
    "thinking" -> Color(0xFFA772FF)
    "connecting" -> Color(0xFFE0A030)
    "error" -> Color(0xFFE5484D)
    else -> Color(0xFF34343F)
  }
  val transition = rememberInfiniteTransition(label = "pulse")
  val pulse by transition.animateFloat(
    initialValue = 1f,
    targetValue = if (running && status != "connecting") 1.07f else 1f,
    animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
    label = "scale",
  )
  Box(Modifier.fillMaxSize().background(Color(0xFF0B0B10)).padding(24.dp)) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
      Spacer(Modifier.height(40.dp))
      Text("Hermes", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
      Text(statusLabel(status, running), color = Color(0xFFA0A0B4), fontSize = 16.sp)
      Spacer(Modifier.weight(1f))
      Box(
        Modifier.size(230.dp).scale(pulse).clip(CircleShape).background(color).clickable { onTap() },
        contentAlignment = Alignment.Center,
      ) {
        Text(if (running) "Hang up" else "Talk", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Medium)
      }
      Spacer(Modifier.height(18.dp))
      if (detail.isNotBlank()) {
        Text(detail, color = Color(0xFF8A8AA3), fontSize = 13.sp, textAlign = TextAlign.Center)
      }
      Spacer(Modifier.weight(1f))
      Column(Modifier.fillMaxWidth()) {
        for ((role, text) in lines) {
          val who = when (role) {
            "user" -> "You"
            "tool" -> "Hermes is"
            else -> "Hermes"
          }
          Text(
            "$who: $text",
            color = if (role == "assistant") Color.White else Color(0xFF9A9AB0),
            fontSize = 14.sp,
            maxLines = 3,
            modifier = Modifier.padding(vertical = 3.dp),
          )
        }
      }
    }
  }
}

fun statusLabel(status: String, running: Boolean): String = when (status) {
  "connecting" -> "Connecting…"
  "listening" -> "Listening"
  "thinking" -> "Hermes is working…"
  "speaking" -> "Speaking"
  "error" -> "Something went wrong"
  else -> if (running) "…" else "Tap to talk"
}
