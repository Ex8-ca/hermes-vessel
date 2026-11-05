package com.chillygeek.hermesvessel

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import androidx.core.app.NotificationCompat
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/** A disposable projection: never reused across screenshots; no image written to disk. */
class ScreenCaptureService : Service() {
  private val handler = Handler(Looper.getMainLooper())
  private var projection: MediaProjection? = null
  private var display: VirtualDisplay? = null
  private var reader: ImageReader? = null
  private var requestId = ""
  private var released = false
  private var captured = false
  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (projection != null) return START_NOT_STICKY
    requestId = intent?.getStringExtra("request_id").orEmpty()
    @Suppress("DEPRECATION")
    val data = intent?.getParcelableExtra<Intent>("consent")
    if (data == null || !ScreenRequests.claim(this, requestId, ::release)) { stopSelf(); return START_NOT_STICKY }
    try {
      val nm = getSystemService(NotificationManager::class.java)
      nm.createNotificationChannel(NotificationChannel("screen_capture", "Single screenshot capture", NotificationManager.IMPORTANCE_LOW))
      val note = NotificationCompat.Builder(this, "screen_capture")
        .setSmallIcon(R.drawable.ic_notification).setContentTitle("Capturing one screenshot")
        .setContentText("Screen sharing stops immediately after this image.").setOngoing(true).build()
      if (Build.VERSION.SDK_INT >= 29) startForeground(43, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
      else startForeground(43, note)
      val p = getSystemService(MediaProjectionManager::class.java).getMediaProjection(Activity.RESULT_OK, data)
        ?: error("No screen projection granted")
      projection = p
      p.registerCallback(object : MediaProjection.Callback() {
        override fun onStop() {
          if (!released) ScreenRequests.finish(this@ScreenCaptureService, error = "Android stopped screen sharing.")
        }
      }, handler)
      val metrics = resources.displayMetrics
      val scale = minOf(1.0, 1280.0 / maxOf(metrics.widthPixels, metrics.heightPixels))
      val width = (metrics.widthPixels * scale).roundToInt().coerceAtLeast(1)
      val height = (metrics.heightPixels * scale).roundToInt().coerceAtLeast(1)
      val r = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
      reader = r
      // Let the system consent window disappear before requesting a display.
      handler.postDelayed({
        if (!ScreenRequests.valid(this, requestId) || released) { release(); return@postDelayed }
        r.setOnImageAvailableListener({ source ->
          val image = try { source.acquireLatestImage() } catch (_: Exception) { null }
          if (image != null) {
            if (captured || !ScreenRequests.valid(this, requestId)) {
              image.close()
              ScreenRequests.finish(this, error = "Screenshot permission expired.")
            } else {
              captured = true
              try {
                val plane = image.planes[0]
                val paddedWidth = plane.rowStride / plane.pixelStride
                val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
                padded.copyPixelsFromBuffer(plane.buffer)
                image.close()
                release() // End access immediately, before compressing or sending the image.
                val bitmap = Bitmap.createBitmap(padded, 0, 0, width, height)
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
                if (bitmap !== padded) bitmap.recycle()
                padded.recycle()
                val encoded = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                ScreenRequests.finish(this, image = "data:image/jpeg;base64,$encoded")
              } catch (_: Exception) {
                image.close()
                ScreenRequests.finish(this, error = "Could not read this screen. Protected content may not be shareable.")
              }
            }
          }
        }, handler)
        try {
          display = p.createVirtualDisplay("Vessel single screenshot", width, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler)
        } catch (_: Exception) { ScreenRequests.finish(this, error = "Android refused screen capture.") }
      }, 700)
      handler.postDelayed({ ScreenRequests.finish(this, error = "No screen image received.") }, 10000)
    } catch (_: Exception) { ScreenRequests.finish(this, error = "Android refused screen capture.") }
    return START_NOT_STICKY
  }

  private fun release() {
    if (released) return
    released = true
    handler.removeCallbacksAndMessages(null)
    reader?.setOnImageAvailableListener(null, null)
    display?.release(); display = null
    reader?.close(); reader = null
    projection?.stop(); projection = null
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  override fun onDestroy() {
    if (ScreenRequests.pending.value?.id == requestId)
      ScreenRequests.finish(this, error = "Screen capture ended.")
    release()
    super.onDestroy()
  }
}
