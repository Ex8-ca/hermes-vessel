package com.chillygeek.hermesvessel

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

@Composable
fun ScreenPermissionSettings(url: String?) {
  val context = LocalContext.current
  var enabled by remember(url) { mutableStateOf(url != null && ScreenRequests.allowed(context, url)) }
  Text("PHONE ACCESS", style = Type.overline)
  Spacer(Modifier.height(8.dp))
  Row(verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {
      Text("Allow Hermes to request screenshots", style = Type.body)
      Text("For this gateway only. You approve each image separately. No continuous screen access.", style = Type.label)
    }
    Switch(modifier = Modifier.semantics { contentDescription = "Allow Hermes to request screenshots" }, checked = enabled, enabled = url != null, onCheckedChange = {
      enabled = it
      url?.let { gateway -> ScreenRequests.setAllowed(context, gateway, it) }
    })
  }
  CallBubbleSettings()
}

/** The floating call button: off by default, and it needs Android's "Display over other apps" permission. */
@Composable
fun CallBubbleSettings() {
  val context = LocalContext.current
  val on by TalkState.bubbleOn.collectAsState()
  val granted by TalkState.overlayGranted.collectAsState()
  fun askPermission() = context.startActivity(
    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
  )
  Spacer(Modifier.height(16.dp))
  Row(verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {
      Text("Floating call button", style = Type.body)
      Text(
        when {
          on && !granted -> "Needs \"Display over other apps\". Tap Allow, then turn on Hermes Vessel."
          else -> "During a call, a small light floats over other apps. Tap it to show Hermes your screen, mute, or come back here; drag it onto ✕ to hide it. A screen you share goes to this gateway and its AI provider, and Android asks you each time. Gone when the call ends."
        },
        style = Type.label,
      )
    }
    Switch(
      modifier = Modifier.semantics { contentDescription = "Floating call button" },
      checked = on && granted,
      onCheckedChange = { want ->
        CallBubble.setEnabled(context, want)
        if (want && !CallBubble.permitted(context)) askPermission()
      },
    )
  }
  if (on && !granted) {
    TextButton(onClick = ::askPermission) { Text("Allow", color = Gold) }
  }
}

@Composable
fun ScreenRequestPrompt() {
  val context = LocalContext.current
  val pending by ScreenRequests.pending.collectAsState()
  var opened by remember { mutableStateOf<String?>(null) }
  val request = pending ?: return
  if (opened == request.id) return
  AlertDialog(
    onDismissRequest = { ScreenRequests.finish(context, error = "Screenshot declined.") },
    title = { Text("Can I see your screen?") },
    text = { Text(request.reason + "\n\nReview and approve one screenshot. Nothing is captured yet.") },
    confirmButton = {
      TextButton(onClick = {
        opened = request.id
        context.startActivity(Intent(context, ScreenConsentActivity::class.java).putExtra("request_id", request.id))
      }) { Text("Review request") }
    },
    dismissButton = {
      TextButton(onClick = { ScreenRequests.finish(context, error = "Screenshot declined. Continue without the screen.") }) { Text("Not now") }
    },
  )
}
