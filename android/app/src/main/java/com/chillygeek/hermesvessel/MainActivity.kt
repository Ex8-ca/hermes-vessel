package com.chillygeek.hermesvessel

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.view.drawToBitmap
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.sin

class MainActivity : ComponentActivity() {
  private val http = OkHttpClient.Builder().callTimeout(6, TimeUnit.SECONDS).build()
  /** The first sample of a voice is recorded on the server, which takes a few seconds. */
  private val sampleHttp = OkHttpClient.Builder().callTimeout(75, TimeUnit.SECONDS).build()
  /** Short name of the paired Hermes host, or null when this phone isn't paired. */
  private val pairedHost = MutableStateFlow<String?>(null)
  private val showSettings = MutableStateFlow(false)
  private var demoJob: Job? = null
  private var preview: MediaPlayer? = null
  private var previewJob: Job? = null
  private val appVersion: String by lazy {
    try {
      packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (e: Exception) {
      ""
    }
  }

  private val permissions =
    registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
      if (hasMic()) {
        startCall()
      } else {
        TalkState.status.value = "error"
        TalkState.detail.value = "Hermes needs the microphone to hear you. Allow it in Settings."
      }
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
      navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
    )
    super.onCreate(savedInstanceState)
    TalkState.selectedVoice.value = Prefs.voice(this).ifBlank { Voices.DEFAULT }
    refreshPairing()
    handleIntent(intent)
    setContent {
      VesselTheme {
        val host by pairedHost.collectAsState()
        val settingsOpen by showSettings.collectAsState()
        VesselApp(
          host = host,
          showSettings = settingsOpen,
          appVersion = appVersion,
          onTalk = ::onTalk,
          onEnd = {
            haptic(HapticFeedbackConstants.VIRTUAL_KEY)
            TalkService.stop(this)
          },
          onMute = {
            TalkState.muted.value = !TalkState.muted.value
            haptic(HapticFeedbackConstants.VIRTUAL_KEY)
          },
          onInterrupt = {
            haptic(HapticFeedbackConstants.VIRTUAL_KEY)
            TalkService.interrupt()
          },
          onPairLink = ::pairFrom,
          onUnpair = ::unpair,
          onOpenSettings = ::openSettings,
          onCloseSettings = ::closeSettings,
          onSelectVoice = ::selectVoice,
          onPreviewVoice = ::togglePreview,
        )
      }
    }
    // Keep the screen awake while a call is on screen.
    lifecycleScope.launch {
      TalkState.running.collect { on ->
        if (on) {
          window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
          window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
          if (demoJob == null) checkHealth()
        }
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    handleIntent(intent)
  }

  override fun onResume() {
    super.onResume()
    if (demoJob == null && !TalkState.running.value) checkHealth()
  }

  override fun onStop() {
    super.onStop()
    stopPreview()
  }

  private fun hasMic() =
    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

  private fun haptic(constant: Int) {
    window.decorView.performHapticFeedback(constant)
  }

  private fun refreshPairing() {
    val url = Prefs.url(this)
    pairedHost.value = if (url.isNotBlank() && Prefs.token(this).isNotBlank()) Pairing.hostLabel(url) else null
  }

  private fun handleIntent(intent: Intent?) {
    if (intent == null) return
    val demo = intent.getStringExtra("demo")
    if (demo != null && isDebuggable()) {
      runDemo(demo)
      if (intent.getBooleanExtra("snap", false)) {
        lifecycleScope.launch {
          delay(2600)
          saveSnapshot(demo)
        }
      }
      return
    }
    val data = intent.data
    if (data != null && data.scheme == "hermesvessel" && data.host == "pair") pairFrom(data.toString())
    if (intent.getBooleanExtra("snap", false) && isDebuggable()) {
      lifecycleScope.launch {
        delay(3500)
        saveSnapshot("live")
      }
    }
  }

  /** Save a pairing link (deep link or pasted page address). */
  private fun pairFrom(text: String): Boolean {
    val link = Pairing.parse(text) ?: return false
    Prefs.save(this, link.wsUrl, link.token)
    TalkState.status.value = "idle"
    TalkState.detail.value = ""
    TalkState.hostOnline.value = null
    refreshPairing()
    haptic(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
    checkHealth()
    return true
  }

  private fun unpair() {
    if (TalkState.running.value) TalkService.stop(this)
    closeSettings()
    Prefs.clear(this)
    TalkState.hostOnline.value = null
    refreshPairing()
  }

  private fun openSettings() {
    TalkState.previewError.value = ""
    showSettings.value = true
    loadVoices()
    if (!TalkState.running.value) checkHealth()
  }

  private fun closeSettings() {
    stopPreview()
    showSettings.value = false
  }

  private fun selectVoice(id: String) {
    Prefs.saveVoice(this, id)
    TalkState.selectedVoice.value = id
    haptic(HapticFeedbackConstants.VIRTUAL_KEY)
  }

  /** Ask the paired Hermes which voices it offers (falls back to the built-in list). */
  private fun loadVoices() {
    val url = Pairing.voicesUrl(Prefs.url(this), Prefs.token(this)) ?: return
    lifecycleScope.launch {
      val result = withContext(Dispatchers.IO) {
        try {
          http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val json = JSONObject(response.body?.string() ?: "{}")
            val array = json.optJSONArray("voices") ?: return@use null
            val list = (0 until array.length()).mapNotNull { i ->
              val o = array.optJSONObject(i) ?: return@mapNotNull null
              val id = o.optString("id")
              if (id.isBlank()) null else Voice(id, o.optString("name", id), o.optString("note"), o.optString("tone"))
            }
            if (list.isEmpty()) null else json.optString("default", Voices.DEFAULT) to list
          }
        } catch (e: Exception) {
          null
        }
      } ?: return@launch
      TalkState.voices.value = result.second
      // No choice made yet: show the voice this Hermes uses by default.
      if (Prefs.voice(this@MainActivity).isBlank()) TalkState.selectedVoice.value = result.first
    }
  }

  /** Play (or stop) a short sample of a voice. Samples are cached on the phone after the first listen. */
  private fun togglePreview(id: String) {
    val same = TalkState.previewing.value == id
    stopPreview()
    if (same || TalkState.running.value) return
    val url = Pairing.sampleUrl(Prefs.url(this), Prefs.token(this), id) ?: return
    TalkState.previewError.value = ""
    TalkState.previewing.value = id
    TalkState.previewLoading.value = true
    previewJob = lifecycleScope.launch {
      val file = java.io.File(cacheDir, "voice-$id.wav")
      val ready = file.length() > 1024 || withContext(Dispatchers.IO) {
        try {
          sampleHttp.newCall(Request.Builder().url(url).build()).execute().use { response ->
            val body = response.body
            if (!response.isSuccessful || body == null) return@use false
            val part = java.io.File(cacheDir, "voice-$id.part")
            body.byteStream().use { input -> part.outputStream().use { input.copyTo(it) } }
            part.renameTo(file)
          }
        } catch (e: Exception) {
          false
        }
      }
      if (TalkState.previewing.value != id) return@launch
      TalkState.previewLoading.value = false
      if (!ready) {
        TalkState.previewing.value = null
        TalkState.previewError.value = "Couldn't get that sample from your Hermes. Try again in a moment."
        return@launch
      }
      try {
        val player = MediaPlayer()
        preview = player
        player.setAudioAttributes(
          AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build(),
        )
        player.setDataSource(file.path)
        player.setOnCompletionListener { if (preview === it) stopPreview() }
        player.prepare()
        player.start()
      } catch (e: Exception) {
        stopPreview()
        file.delete()
      }
    }
  }

  private fun stopPreview() {
    previewJob?.cancel()
    previewJob = null
    preview?.let {
      try {
        it.stop()
      } catch (e: Exception) {
        // not started yet
      }
      it.release()
    }
    preview = null
    TalkState.previewing.value = null
    TalkState.previewLoading.value = false
  }

  /** Ask the paired Hermes whether it's reachable, so the chip is honest before you tap. */
  private fun checkHealth() {
    val url = Pairing.healthUrl(Prefs.url(this)) ?: return
    lifecycleScope.launch {
      val ok = withContext(Dispatchers.IO) {
        try {
          http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            response.isSuccessful && JSONObject(response.body?.string() ?: "{}").optBoolean("ok")
          }
        } catch (e: Exception) {
          false
        }
      }
      TalkState.hostOnline.value = ok
    }
  }

  private fun onTalk() {
    if (TalkState.running.value) return
    if (pairedHost.value == null) return
    stopPreview()
    val wanted = mutableListOf(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
    if (Build.VERSION.SDK_INT >= 31) wanted += Manifest.permission.BLUETOOTH_CONNECT
    val missing = wanted.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
    if (missing.isEmpty()) startCall() else permissions.launch(missing.toTypedArray())
  }

  private fun startCall() {
    haptic(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
    TalkState.status.value = "connecting"
    TalkService.start(this)
  }

  private fun isDebuggable() = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

  /** Debug builds only: render the window to <external files>/<name>.png (works on headless emulators). */
  private fun saveSnapshot(name: String) {
    try {
      val bitmap = window.decorView.drawToBitmap()
      val dir = getExternalFilesDir(null) ?: filesDir
      java.io.File(dir, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
      android.util.Log.i("VesselDemo", "snapshot saved: $dir/$name.png")
    } catch (e: Exception) {
      android.util.Log.e("VesselDemo", "snapshot failed", e)
    }
  }

  /**
   * Debug builds only: show a state without a server, for screenshots and design work.
   * adb shell am start -n com.chillygeek.hermesvessel/.MainActivity --es demo listening
   */
  private fun runDemo(state: String) {
    demoJob?.cancel()
    val s = TalkState
    s.resetCall()
    showSettings.value = state == "settings"
    if (state == "settings") {
      s.selectedVoice.value = "marin"
      s.previewing.value = "shimmer"
    }
    pairedHost.value = if (state == "unpaired") null else "ai5080"
    s.hostOnline.value = state != "offline"
    s.running.value = state in setOf("connecting", "listening", "speaking", "thinking", "muted")
    s.status.value = when (state) {
      "muted", "speaking" -> "listening"
      "offline", "unpaired", "ended" -> "idle"
      else -> state
    }
    s.muted.value = state == "muted"
    s.route.value = "Through Pixel Buds Pro"
    if (state in setOf("listening", "speaking", "thinking", "muted")) {
      s.addLine("user", "How much space is left on the server?")
    }
    when (state) {
      "thinking" -> {
        s.addLine("assistant", "One sec, I'll ask Hermes.")
        s.detail.value = "Checking free space on the root filesystem"
        s.thinkingSince.value = System.currentTimeMillis() - 12_000
      }
      "speaking" -> s.liveCaption.value =
        "You've got about 37 gigabytes free on the root filesystem. The disk is around 92 percent used, so it's getting full."
      "error" -> s.detail.value = "Couldn't reach Hermes (timeout)"
      "ended" -> s.lastCall.value = "Call ended \u00b7 2:31"
    }
    demoJob = lifecycleScope.launch {
      var t = 0.0
      while (true) {
        t += 0.1
        val wave = ((sin(t * 2.3) + 0.5 * sin(t * 5.7) + 1.5) / 3.0).toFloat().coerceIn(0f, 1f)
        if (state == "speaking") {
          s.playing.value = true
          s.outLevel.value = wave
        }
        if (state == "listening") s.micLevel.value = wave * 0.85f
        delay(100)
      }
    }
  }
}
