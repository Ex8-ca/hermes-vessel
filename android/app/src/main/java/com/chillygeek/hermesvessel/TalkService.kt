package com.chillygeek.hermesvessel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * One voice call with Hermes. Runs as a foreground service so the call keeps
 * going with the screen off or the phone in a pocket.
 *
 * Wire format to the plugin server: binary frames are 16-bit mono PCM at
 * 24 kHz in both directions; text frames are small JSON control messages.
 */
class TalkService : Service() {
  companion object {
    private const val TAG = "TalkService"
    private const val ACTION_STOP = "com.chillygeek.hermesvessel.STOP"
    private const val SAMPLE_RATE = 24000
    private const val CHUNK_BYTES = 4800 // 100 ms
    private const val BYTES_PER_MS = 48 // 24 kHz x 2 bytes

    @Volatile private var instance: TalkService? = null

    fun start(context: Context) {
      ContextCompat.startForegroundService(context, Intent(context, TalkService::class.java))
    }

    fun stop(context: Context) {
      context.startService(Intent(context, TalkService::class.java).setAction(ACTION_STOP))
    }

    /** Stop Hermes mid-sentence (tap on the light). */
    fun interrupt() {
      instance?.interruptSpeech()
    }

    /** Loudness 0..1 of a PCM16 little-endian chunk, on a speech-friendly dB curve. */
    fun level(buf: ByteArray, n: Int): Float {
      var sum = 0.0
      var count = 0
      var i = 0
      while (i + 1 < n) {
        val s = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toInt()
        sum += (s * s).toDouble()
        count++
        i += 2
      }
      if (count == 0) return 0f
      val rms = sqrt(sum / count).coerceAtLeast(1.0)
      val db = 20.0 * log10(rms / 32768.0)
      return ((db + 55.0) / 45.0).toFloat().coerceIn(0f, 1f)
    }
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val client =
    OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
  private val playQueue = Channel<ByteArray>(Channel.UNLIMITED)
  private var socket: WebSocket? = null
  private var recorder: AudioRecord? = null
  private var track: AudioTrack? = null
  private var audioManager: AudioManager? = null
  @Volatile private var running = false
  @Volatile private var playEndAt = 0L
  private var callStartedAt = 0L

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP) {
      shutdown()
      stopSelf()
      return START_NOT_STICKY
    }
    if (running) return START_NOT_STICKY
    startInForeground()
    instance = this
    running = true
    TalkState.resetCall()
    TalkState.running.value = true
    TalkState.status.value = "connecting"
    callStartedAt = SystemClock.elapsedRealtime()
    routeAudio()
    connect()
    return START_NOT_STICKY
  }

  private fun startInForeground() {
    val nm = getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(NotificationChannel("talk", "Voice calls with Hermes", NotificationManager.IMPORTANCE_LOW))
    val stopIntent = PendingIntent.getService(
      this, 1, Intent(this, TalkService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
    )
    val openIntent = PendingIntent.getActivity(
      this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
    )
    val notification: Notification = NotificationCompat.Builder(this, "talk")
      .setSmallIcon(R.drawable.ic_notification)
      .setContentTitle("Talking with Hermes")
      .setContentText("Tap to open")
      .setContentIntent(openIntent)
      .addAction(0, "End call", stopIntent)
      .setOngoing(true)
      .setCategory(NotificationCompat.CATEGORY_CALL)
      .build()
    if (Build.VERSION.SDK_INT >= 30) {
      startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    } else {
      startForeground(1, notification)
    }
  }

  /** Prefer a headset (Bluetooth or wired); otherwise the phone speaker. */
  @Suppress("DEPRECATION")
  private fun routeAudio() {
    val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
    audioManager = am
    am.mode = AudioManager.MODE_IN_COMMUNICATION
    if (Build.VERSION.SDK_INT >= 31) {
      val devices = am.availableCommunicationDevices
      val headset =
        devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLE_HEADSET || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
          ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
      val target = headset ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
      if (target != null) am.setCommunicationDevice(target)
      val name = headset?.productName?.toString()?.takeIf { it.isNotBlank() }
      TalkState.route.value = if (headset != null) "Through ${name ?: "your headset"}" else "On the phone speaker"
    } else if (am.isBluetoothScoAvailableOffCall) {
      am.startBluetoothSco()
      am.isBluetoothScoOn = true
      TalkState.route.value = "Through your Bluetooth headset"
    } else {
      am.isSpeakerphoneOn = true
      TalkState.route.value = "On the phone speaker"
    }
  }

  private fun connect() {
    val url = Prefs.url(this)
    val token = Prefs.token(this)
    if (url.isBlank() || token.isBlank()) {
      fail("This phone isn't paired yet.")
      return
    }
    val full = url + (if (url.contains("?")) "&" else "?") + "t=" + URLEncoder.encode(token, "UTF-8")
    socket = client.newWebSocket(
      Request.Builder().url(full).build(),
      object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
          startAudio()
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
          playQueue.trySend(bytes.toByteArray())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
          handleControl(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
          webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
          if (running) finish()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
          if (!running) return
          val why = when {
            response?.code == 403 -> "Hermes didn't accept this phone. Pair it again."
            else -> "Couldn't reach Hermes (" + (t.message ?: t.javaClass.simpleName) + ")"
          }
          fail(why)
        }
      },
    )
  }

  @Suppress("MissingPermission")
  private fun startAudio() {
    try {
      val minRec = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
      val rec = AudioRecord(
        MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT, maxOf(minRec, CHUNK_BYTES * 4),
      )
      if (rec.state != AudioRecord.STATE_INITIALIZED) {
        rec.release()
        fail("The microphone isn't available.")
        return
      }
      val minPlay = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
      val tr = AudioTrack.Builder()
        .setAudioAttributes(
          AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build(),
        )
        .setAudioFormat(
          AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build(),
        )
        .setBufferSizeInBytes(maxOf(minPlay, CHUNK_BYTES * 4))
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()
      recorder = rec
      track = tr
      tr.play()
      rec.startRecording()
      // Microphone -> server (skipped while muted).
      scope.launch {
        val buf = ByteArray(CHUNK_BYTES)
        while (running) {
          val n = rec.read(buf, 0, buf.size)
          if (n <= 0) continue
          if (TalkState.muted.value) {
            TalkState.micLevel.value = 0f
            continue
          }
          TalkState.micLevel.value = level(buf, n)
          socket?.send(buf.toByteString(0, n))
        }
      }
      // Server -> speaker. Track when the queued audio will actually finish playing.
      scope.launch {
        for (chunk in playQueue) {
          if (!running) break
          val now = SystemClock.elapsedRealtime()
          playEndAt = max(now, playEndAt) + chunk.size / BYTES_PER_MS
          TalkState.outLevel.value = level(chunk, chunk.size)
          tr.write(chunk, 0, chunk.size)
        }
      }
      // "Speaking" means audible, not "the server finished generating".
      scope.launch {
        while (running) {
          val playing = SystemClock.elapsedRealtime() < playEndAt
          if (TalkState.playing.value != playing) TalkState.playing.value = playing
          if (!playing && TalkState.outLevel.value != 0f) TalkState.outLevel.value = 0f
          delay(80)
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "audio start failed", e)
      fail("Audio failed: " + (e.message ?: e.javaClass.simpleName))
    }
  }

  private fun interruptSpeech() {
    socket?.send("{\"type\":\"interrupt\"}")
    dropPlayback()
  }

  private fun dropPlayback() {
    while (playQueue.tryReceive().isSuccess) { }
    track?.let { t -> try { t.pause(); t.flush(); t.play() } catch (_: Exception) { } }
    playEndAt = 0L
    TalkState.playing.value = false
    TalkState.outLevel.value = 0f
  }

  private fun handleControl(text: String) {
    val msg = try { JSONObject(text) } catch (e: Exception) { return }
    when (msg.optString("type")) {
      "state" -> {
        val state = msg.optString("state")
        val previous = TalkState.status.value
        TalkState.status.value = state
        if (state == "thinking") {
          TalkState.detail.value = msg.optString("detail")
          if (previous != "thinking") TalkState.thinkingSince.value = System.currentTimeMillis()
        } else {
          TalkState.thinkingSince.value = 0L
        }
      }
      "clear" -> {
        // You started talking: drop whatever Hermes was still saying.
        dropPlayback()
        TalkState.liveCaption.value = ""
      }
      "caption" -> TalkState.liveCaption.value = TalkState.liveCaption.value + msg.optString("delta")
      "transcript" -> {
        val role = msg.optString("role")
        TalkState.addLine(role, msg.optString("text"))
        if (role == "assistant") TalkState.liveCaption.value = ""
      }
      "error" -> {
        TalkState.status.value = "error"
        TalkState.detail.value = msg.optString("message")
      }
    }
  }

  private fun fail(message: String) {
    TalkState.status.value = "error"
    TalkState.detail.value = message
    shutdown()
    stopSelf()
  }

  private fun finish() {
    shutdown()
    stopSelf()
  }

  @Suppress("DEPRECATION")
  @Synchronized
  private fun shutdown() {
    if (callStartedAt > 0L) {
      val secs = (SystemClock.elapsedRealtime() - callStartedAt) / 1000
      if (TalkState.status.value != "error") {
        TalkState.lastCall.value = "Call ended \u00b7 %d:%02d".format(secs / 60, secs % 60)
      }
      callStartedAt = 0L
    }
    running = false
    instance = null
    TalkState.running.value = false
    TalkState.playing.value = false
    TalkState.micLevel.value = 0f
    TalkState.outLevel.value = 0f
    TalkState.thinkingSince.value = 0L
    TalkState.muted.value = false
    try { socket?.close(1000, "bye") } catch (_: Exception) { }
    socket = null
    recorder?.let { r -> try { r.stop() } catch (_: Exception) { }; r.release() }
    recorder = null
    track?.let { t -> try { t.pause(); t.flush(); t.stop() } catch (_: Exception) { }; t.release() }
    track = null
    while (playQueue.tryReceive().isSuccess) { }
    playEndAt = 0L
    audioManager?.let { am ->
      if (Build.VERSION.SDK_INT >= 31) {
        am.clearCommunicationDevice()
      } else {
        am.stopBluetoothSco()
        am.isSpeakerphoneOn = false
      }
      am.mode = AudioManager.MODE_NORMAL
    }
    audioManager = null
    if (TalkState.status.value != "error") TalkState.status.value = "idle"
    stopForeground(STOP_FOREGROUND_REMOVE)
  }

  override fun onDestroy() {
    shutdown()
    scope.cancel()
    super.onDestroy()
  }
}
