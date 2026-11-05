package com.chillygeek.hermesvessel

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/** Private activity entered only by a tap; a new system consent dialog for EVERY request. */
class ScreenConsentActivity : ComponentActivity() {
  private var requestId = ""
  private var promptLaunched = false
  private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
    if (result.resultCode == Activity.RESULT_OK && result.data != null && ScreenRequests.valid(this, requestId)) {
      try {
        ContextCompat.startForegroundService(this, Intent(this, ScreenCaptureService::class.java)
          .putExtra("request_id", requestId).putExtra("consent", result.data))
      } catch (_: Exception) {
        ScreenRequests.finish(this, error = "Android could not start screen capture.")
      }
    } else if (ScreenRequests.pending.value?.id == requestId) {
      ScreenRequests.finish(this, error = "Screenshot declined. Continue without seeing the screen.")
    }
    finish()
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    requestId = intent.getStringExtra("request_id").orEmpty()
    if (!ScreenRequests.valid(this, requestId)) { finish(); return }
    lifecycleScope.launch {
      ScreenRequests.pending.collect { if (it?.id != requestId) finish() }
    }
    // ActivityResultRegistry restores an in-flight OS prompt after rotation; don't launch twice.
    promptLaunched = savedInstanceState?.getBoolean("prompt_launched") ?: false
    if (promptLaunched) return
    val request = ScreenRequests.pending.value!!
    AlertDialog.Builder(this)
      .setTitle("Share one screenshot with Hermes?")
      .setMessage("${request.reason}\n\nOne image will be sent to ${Pairing.displayPath(request.gateway)} and its AI provider. Sensitive information visible on screen may be included. Sharing stops immediately after one image. Android will ask you to approve next.")
      .setPositiveButton("Continue") { _, _ ->
        if (ScreenRequests.valid(this, requestId)) {
          promptLaunched = true
          consent.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
        } else finish()
      }
      .setNegativeButton("Not now") { _, _ -> decline() }
      .setOnCancelListener { decline() }
      .show()
  }

  override fun onSaveInstanceState(outState: Bundle) {
    outState.putBoolean("prompt_launched", promptLaunched)
    super.onSaveInstanceState(outState)
  }

  private fun decline() {
    if (ScreenRequests.pending.value?.id == requestId)
      ScreenRequests.finish(this, error = "Screenshot declined. Continue without seeing the screen.")
    finish()
  }
}
