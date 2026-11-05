package com.chillygeek.hermesvessel

import android.content.Intent
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
