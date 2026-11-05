package com.chillygeek.hermesvessel

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

/** In-memory, single-flight requests bound to a particular live call. Never stores consent. */
object ScreenRequests {
  data class Request(val id: String, val gateway: String, val reason: String)
  val pending = MutableStateFlow<Request?>(null)
  private val main = Handler(Looper.getMainLooper())
  private var gateway: String? = null
  private var send: ((JSONObject) -> Unit)? = null
  private var cleanup: (() -> Unit)? = null
  private var claimed = false
  private var timeout: Runnable? = null
  private const val NOTICE = 42

  fun allowed(c: Context, url: String) = c.getSharedPreferences("vessel", 0)
    .getBoolean("screen:$url", false)

  fun setAllowed(c: Context, url: String, enabled: Boolean) {
    c.getSharedPreferences("vessel", 0).edit().putBoolean("screen:$url", enabled).apply()
    if (!enabled && pending.value?.gateway == url) finish(c, error = "Screen permission was turned off.")
    advertise(c)
  }

  fun attach(c: Context, url: String, sender: (JSONObject) -> Unit) {
    detach(c)
    gateway = url
    send = sender
    advertise(c)
  }

  private fun advertise(c: Context) {
    val url = gateway ?: return
    send?.invoke(JSONObject().put("type", "capabilities").put("screenshot", allowed(c, url)))
  }

  fun detach(c: Context) {
    finish(c, error = "Call ended.")
    gateway = null
    send = null
  }

  fun valid(c: Context, id: String): Boolean {
    val request = pending.value ?: return false
    return request.id == id && request.gateway == gateway && send != null &&
      TalkState.running.value && allowed(c, request.gateway) && Prefs.url(c) == request.gateway
  }

  fun request(c: Context, id: String, reason: String) {
    val url = gateway
    if (id.isBlank() || id.length > 128) return
    if (url == null || !TalkState.running.value || !allowed(c, url) || Prefs.url(c) != url || pending.value != null) {
      send?.invoke(JSONObject().put("type", "device_result").put("id", id)
        .put("error", "Screen request disabled, unavailable, or another request is pending."))
      return
    }
    claimed = false
    pending.value = Request(id, url, reason.take(300))
    timeout = Runnable { finish(c, error = "Screen approval timed out.") }.also { main.postDelayed(it, 60000) }
    val nm = c.getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(NotificationChannel("screen_requests", "Screenshot requests", NotificationManager.IMPORTANCE_DEFAULT))
    val intent = Intent(c, ScreenConsentActivity::class.java).putExtra("request_id", id)
    val tap = PendingIntent.getActivity(c, NOTICE, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    try {
      nm.notify(NOTICE, NotificationCompat.Builder(c, "screen_requests")
        .setSmallIcon(R.drawable.ic_notification).setContentTitle("Hermes would like one screenshot")
        .setContentText("Tap to review. Nothing is captured until you approve.")
        .setContentIntent(tap).setAutoCancel(true).build())
    } catch (_: SecurityException) { /* In-app approval remains available if notifications are denied. */ }
  }

  fun claim(c: Context, id: String, release: () -> Unit): Boolean {
    if (!valid(c, id) || claimed) return false
    claimed = true
    cleanup = release
    return true
  }

  fun finish(c: Context, image: String? = null, error: String? = null) {
    val request = pending.value ?: return
    val canSendImage = image != null && valid(c, request.id) && claimed
    pending.value = null
    timeout?.let(main::removeCallbacks)
    timeout = null
    val release = cleanup
    cleanup = null
    release?.invoke() // Stop projection BEFORE transmitting the single image.
    c.getSystemService(NotificationManager::class.java).cancel(NOTICE)
    send?.invoke(JSONObject().put("type", "device_result").put("id", request.id).apply {
      if (canSendImage) put("image", image)
      else put("error", error ?: "Screenshot cancelled or permission revoked.")
    })
  }
}
