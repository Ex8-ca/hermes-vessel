package com.chillygeek.hermesvessel

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A floating call button ("chat head") drawn over other apps during a call. Tap it and its controls
 * fan out around it; drag it to either edge, or onto the ✕ at the bottom to hide it for now.
 *
 * Platform rules it follows:
 * - Off by default; shown only after the user turns it on AND grants "Display over other apps"
 *   (SYSTEM_ALERT_WINDOW). TYPE_APPLICATION_OVERLAY, never takes focus, and every window is only as big
 *   as what it draws. The one window that lets touches pass through (the ✕ drop target) is
 *   FLAG_NOT_TOUCHABLE with alpha 0.8, the most Android 12+ allows for pass-through overlays.
 * - Battery: it lives inside TalkService, the call's existing foreground service. No extra service,
 *   no wakelock, no polling; it is removed the moment the call ends. Animations are one-shot (dimming
 *   when idle, the menu fanning out, a short pulse when Hermes asks for the screen) and are skipped
 *   when the user has turned animations off.
 * - It hides while Vessel itself is on screen and while a screenshot is being taken, so it is never
 *   in the captured image.
 * - Accessibility: 56dp bubble and 52dp menu buttons, labelled for TalkBack, a live description when
 *   Hermes asks for the screen, and every control (including moving and hiding it) is also an
 *   accessibility action on the bubble, so nothing requires dragging or precise timing.
 */
// ClickableViewAccessibility: the drag listener calls performClick() on a tap; the menu's own listener only
// handles taps on its empty middle or outside it, which close the menu and are not clicks on a control.
@SuppressLint("ClickableViewAccessibility")
class CallBubble(private val context: Context) {
  companion object {
    private const val PREF = "bubble"
    private const val IDLE_MS = 3500L
    fun enabled(c: Context) = c.getSharedPreferences("vessel", 0).getBoolean(PREF, false)
    fun setEnabled(c: Context, on: Boolean) {
      c.getSharedPreferences("vessel", 0).edit().putBoolean(PREF, on).apply()
      TalkState.bubbleOn.value = on
    }
    fun permitted(c: Context) = Settings.canDrawOverlays(c)
  }

  private val wm = context.getSystemService(WindowManager::class.java)
  private val prefs = context.getSharedPreferences("vessel", 0)
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val main = Handler(Looper.getMainLooper())
  private val density = context.resources.displayMetrics.density
  private fun dp(v: Int) = (v * density).roundToInt()
  private val size = dp(56)
  private val margin = dp(8)
  private val radius = dp(100) // menu buttons sit on this arc around the bubble's centre
  private val button = dp(52)
  private val itemW = dp(84)
  private val itemH = dp(78)

  /** Dropped on the ✕: hidden until the user leaves Vessel again (or the next call). */
  private val dismissed = MutableStateFlow(false)
  private var bubble: FrameLayout? = null
  private var params: WindowManager.LayoutParams? = null
  private var badge: View? = null
  private var muteIcon: ImageView? = null
  private var menu: View? = null
  private var menuKey: Any? = null
  private var target: View? = null
  private var pulse: ObjectAnimator? = null
  private val actionIds = mutableListOf<Int>()
  private val dim = Runnable { setDimmed(true) }

  fun start() {
    TalkState.bubbleOn.value = enabled(context)
    scope.launch {
      combine(TalkState.running, TalkState.appVisible, ScreenRequests.capturing, TalkState.bubbleOn, dismissed) { running, appVisible, capturing, on, gone ->
        running && on && !gone && !appVisible && !capturing && permitted(context)
      }.distinctUntilChanged().collect { if (it) show() else hide() }
    }
    scope.launch { TalkState.appVisible.collect { if (it) dismissed.value = false } }
    scope.launch {
      combine(ScreenRequests.pending, TalkState.muted) { _, _ -> }.collect { refresh() }
    }
  }

  fun stop() {
    hide()
    scope.cancel()
  }

  // ------------------------------------------------------------------ geometry

  private fun screen(): Pair<Int, Int> =
    if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds.let { it.width() to it.height() }
    else context.resources.displayMetrics.let { it.widthPixels to it.heightPixels }

  private fun clampY(y: Int, height: Int): Int {
    val (_, h) = screen()
    return y.coerceIn(dp(32), (h - height - dp(56)).coerceAtLeast(dp(32)))
  }

  private fun onLeft(): Boolean = (params?.x ?: 0) + size / 2 < screen().first / 2

  // ------------------------------------------------------------------ bubble

  private fun show() {
    if (bubble != null) return
    addTarget()
    val (w, h) = screen()
    val frame = FrameLayout(context)
    frame.addView(View(context).apply {
      background = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        gradientType = GradientDrawable.RADIAL_GRADIENT
        gradientRadius = size * 0.62f
        setGradientCenter(0.44f, 0.40f)
        colors = intArrayOf(0xFFFDF0D4.toInt(), 0xFFF6CD84.toInt(), 0xFFE9AE5B.toInt(), 0xFFD18F45.toInt())
        setStroke(dp(2), 0x8C0D0B09.toInt()) // A dark ring keeps it visible on light screens.
      }
    }, FrameLayout.LayoutParams(size, size))
    muteIcon = ImageView(context).apply {
      setImageResource(R.drawable.ic_mic_off)
      setColorFilter(0xFF0D0B09.toInt())
      importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    frame.addView(muteIcon, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
    badge = View(context).apply {
      background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFF07A6E.toInt()); setStroke(dp(2), 0xFF0D0B09.toInt()) }
      importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    frame.addView(badge, FrameLayout.LayoutParams(dp(16), dp(16), Gravity.TOP or Gravity.END))
    frame.isClickable = true
    frame.isFocusable = true
    frame.setOnClickListener { toggleMenu() }
    ViewCompat.setAccessibilityLiveRegion(frame, ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE)
    frame.setOnTouchListener(Dragger())

    val p = WindowManager.LayoutParams(
      size, size,
      WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
      PixelFormat.TRANSLUCENT,
    ).apply {
      gravity = Gravity.TOP or Gravity.START
      x = prefs.getInt("bubble_x", w - size - margin).coerceIn(margin, w - size - margin)
      y = clampY(prefs.getInt("bubble_y", h / 3), size)
      title = "Hermes Vessel call button"
    }
    try {
      wm.addView(frame, p)
    } catch (_: Exception) {
      return // Permission revoked between the check and now: quietly show nothing.
    }
    bubble = frame
    params = p
    refresh()
    scheduleDim()
  }

  private fun hide() {
    closeMenu()
    removeTarget()
    main.removeCallbacks(dim)
    pulse?.cancel()
    pulse = null
    bubble?.let { runCatching { wm.removeViewImmediate(it) } }
    bubble = null
    params = null
  }

  private fun hermesWaiting() = ScreenRequests.pending.value?.let { !it.fromUser } == true

  /** Dim when left alone, like other floating buttons, so it gets out of the way of the app underneath. */
  private fun scheduleDim() {
    main.removeCallbacks(dim)
    setDimmed(false)
    main.postDelayed(dim, IDLE_MS)
  }

  private fun setDimmed(dimmed: Boolean) {
    val frame = bubble ?: return
    val to = if (dimmed && menu == null && !hermesWaiting()) 0.5f else 1f
    if (ValueAnimator.areAnimatorsEnabled()) frame.animate().alpha(to).setDuration(250).start() else frame.alpha = to
  }

  private fun refresh() {
    val frame = bubble ?: return
    val waiting = hermesWaiting()
    val muted = TalkState.muted.value
    badge?.visibility = if (waiting) View.VISIBLE else View.GONE
    muteIcon?.visibility = if (muted) View.VISIBLE else View.GONE
    frame.contentDescription = when {
      waiting -> "Hermes would like one screenshot. Double-tap to review."
      muted -> "Hermes call controls. Microphone muted."
      else -> "Hermes call controls"
    }
    if (waiting) {
      setDimmed(false)
      if (pulse == null && ValueAnimator.areAnimatorsEnabled()) {
        pulse = ObjectAnimator.ofPropertyValuesHolder(
          frame, PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.12f), PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.12f),
        ).apply {
          duration = 700
          repeatMode = ValueAnimator.REVERSE
          repeatCount = 7 // ~5 s, then it rests; the badge stays.
          start()
        }
      }
    } else {
      pulse?.cancel()
      pulse = null
      frame.scaleX = 1f
      frame.scaleY = 1f
    }
    // Every control is also an accessibility action, so nothing needs a drag or the menu.
    actionIds.forEach { ViewCompat.removeAccessibilityAction(frame, it) }
    actionIds.clear()
    ViewCompat.replaceAccessibilityAction(frame, AccessibilityActionCompat.ACTION_CLICK,
      if (waiting) "review the screenshot request" else "show call controls", null)
    fun action(label: String, run: () -> Unit) {
      actionIds += ViewCompat.addAccessibilityAction(frame, label) { _, _ -> run(); true }
    }
    if (ScreenRequests.pending.value == null) action("Show Hermes this screen") { shareScreen() }
    action(if (muted) "Unmute" else "Mute") { toggleMute() }
    action("Open Hermes Vessel") { openApp() }
    action("Move to the other side") { moveToOtherSide() }
    action("Hide for now") { dismissed.value = true }
    action("End call") { endCall() }
    if (menu != null && menuKey != contentKey()) { closeMenu(); openMenu() } // Keep an open menu in step.
  }

  private inner class Dragger : View.OnTouchListener {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var dragging = false

    override fun onTouch(v: View, e: MotionEvent): Boolean {
      val p = params ?: return false
      when (e.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
          downX = e.rawX; downY = e.rawY; startX = p.x; startY = p.y; dragging = false
          main.removeCallbacks(dim)
          setDimmed(false)
        }
        MotionEvent.ACTION_MOVE -> {
          val dx = e.rawX - downX
          val dy = e.rawY - downY
          if (!dragging && hypot(dx, dy) > slop) { dragging = true; closeMenu(); target?.visibility = View.VISIBLE }
          if (dragging) {
            val (w, h) = screen()
            p.x = (startX + dx.roundToInt()).coerceIn(0, w - size)
            p.y = (startY + dy.roundToInt()).coerceIn(0, h - size)
            runCatching { wm.updateViewLayout(v, p) }
            target?.let { t ->
              val scale = if (overTarget()) 1.25f else 1f
              t.scaleX = scale
              t.scaleY = scale
            }
          }
        }
        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
          if (dragging) {
            val drop = overTarget()
            target?.visibility = View.INVISIBLE
            if (drop) { dismissed.value = true; return true }
            snap()
          } else if (e.actionMasked == MotionEvent.ACTION_UP) v.performClick()
          scheduleDim()
        }
      }
      return true
    }
  }

  /** Rest against the nearest side edge, out of the way of the app underneath. */
  private fun snap(toLeft: Boolean? = null) {
    val v = bubble ?: return
    val p = params ?: return
    val (w, _) = screen()
    val left = toLeft ?: onLeft()
    p.x = if (left) margin else w - size - margin
    p.y = clampY(p.y, size)
    runCatching { wm.updateViewLayout(v, p) }
    prefs.edit().putInt("bubble_x", p.x).putInt("bubble_y", p.y).apply()
  }

  private fun moveToOtherSide() {
    closeMenu()
    snap(toLeft = !onLeft())
  }

  // ------------------------------------------------------------------ ✕ drop target

  private fun targetCenter(): Pair<Int, Int> = screen().let { (w, h) -> w / 2 to h - dp(120) }

  private fun overTarget(): Boolean {
    val p = params ?: return false
    val (tx, ty) = targetCenter()
    return hypot((p.x + size / 2 - tx).toFloat(), (p.y + size / 2 - ty).toFloat()) < dp(72)
  }

  /**
   * Added (invisible) just before the bubble, so the bubble always draws above it; shown only while dragging.
   * Its window has room around the circle so the "you're over it" grow isn't clipped.
   */
  private fun addTarget() {
    if (target != null) return
    val s = dp(96)
    val (tx, ty) = targetCenter()
    val circle = FrameLayout(context).apply {
      background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xE6181310.toInt()); setStroke(dp(2), 0xFFF1EADF.toInt()) }
      addView(ImageView(context).apply { setImageResource(R.drawable.ic_close); setColorFilter(0xFFF1EADF.toInt()) },
        FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
    }
    val view = FrameLayout(context).apply {
      addView(circle, FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER))
      visibility = View.INVISIBLE
      importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }
    val p = WindowManager.LayoutParams(
      s, s,
      WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
      PixelFormat.TRANSLUCENT,
    ).apply {
      gravity = Gravity.TOP or Gravity.START
      x = tx - s / 2
      y = ty - s / 2
      alpha = 0.8f // Pass-through overlays must be at most 0.8 opaque on Android 12+.
      title = "Hide Hermes call button"
    }
    try { wm.addView(view, p); target = view } catch (_: Exception) { }
  }

  private fun removeTarget() {
    target?.let { runCatching { wm.removeViewImmediate(it) } }
    target = null
  }

  // ------------------------------------------------------------------ radial menu

  private data class Item(val label: String, @DrawableRes val icon: Int, val fill: Int, val tint: Int, val run: () -> Unit)

  private fun contentKey(): Any = Triple(ScreenRequests.pending.value?.id, ScreenRequests.pending.value?.fromUser, TalkState.muted.value)

  private fun items(): List<Item> {
    val muted = TalkState.muted.value
    val request = ScreenRequests.pending.value
    val dark = 0xF2181310.toInt()
    val ivory = 0xFFF1EADF.toInt()
    val ink = 0xFF0D0B09.toInt()
    return listOfNotNull(
      when {
        request != null && !request.fromUser -> Item("Review", R.drawable.ic_screenshot, 0xFFF6CD84.toInt(), ink) { review() }
        request == null -> Item("Show screen", R.drawable.ic_screenshot, dark, 0xFFF6CD84.toInt()) { shareScreen() }
        else -> null // Already sharing: nothing to start.
      },
      Item(if (muted) "Unmute" else "Mute", if (muted) R.drawable.ic_mic_off else R.drawable.ic_mic,
        if (muted) ivory else dark, if (muted) ink else ivory) { toggleMute() },
      Item("Open Vessel", R.drawable.ic_open, dark, ivory) { openApp() },
      Item("End call", R.drawable.ic_call_end, 0xFFE5484D.toInt(), 0xFFFFFFFF.toInt()) { endCall() },
    )
  }

  private fun toggleMenu() {
    when {
      menu != null -> closeMenu()
      hermesWaiting() -> review()
      else -> openMenu()
    }
  }

  private fun openMenu() {
    val bv = bubble ?: return
    val bp = params ?: return
    val (w, h) = screen()
    // Room for the top and bottom buttons: nudge the bubble away from the screen's top or bottom first.
    val reach = radius + itemH / 2 + dp(8)
    val cy0 = bp.y + size / 2
    val cy = cy0.coerceIn(reach + dp(24), (h - reach - dp(48)).coerceAtLeast(reach + dp(24)))
    if (cy != cy0) {
      bp.y = cy - size / 2
      runCatching { wm.updateViewLayout(bv, bp) }
    }
    val cx = bp.x + size / 2
    val left = onLeft()
    val list = items()
    // Fan the buttons over a half circle facing away from the edge, top to bottom.
    val centres = list.indices.map { i ->
      val t = if (list.size == 1) 0.5 else i.toDouble() / (list.size - 1)
      val angle = Math.toRadians(-90.0 + 180.0 * t) // -90 = up, 90 = down
      val dx = cos(angle) * radius * (if (left) 1 else -1)
      val dy = sin(angle) * radius
      (cx + dx).roundToInt() to (cy + dy).roundToInt()
    }
    // The window covers the bubble and the arc, no more, clipped to the screen.
    val minX = minOf(bp.x, centres.minOf { it.first } - itemW / 2).coerceAtLeast(0)
    val maxX = maxOf(bp.x + size, centres.maxOf { it.first } + itemW / 2).coerceAtMost(w)
    val minY = (centres.minOf { it.second } - button / 2).coerceAtLeast(0)
    val maxY = (centres.maxOf { it.second } - button / 2 + itemH).coerceAtMost(h)

    val root = FrameLayout(context).apply {
      // A tap on the empty middle (over the bubble) or anywhere outside the menu closes it.
      setOnTouchListener { _, e ->
        if (e.actionMasked == MotionEvent.ACTION_OUTSIDE || e.actionMasked == MotionEvent.ACTION_UP) { closeMenu(); scheduleDim() }
        true
      }
      ViewCompat.setAccessibilityPaneTitle(this, "Hermes call controls")
    }
    list.zip(centres).forEach { (item, c) ->
      val cell = menuItem(item)
      root.addView(cell, FrameLayout.LayoutParams(itemW, itemH).apply {
        leftMargin = c.first - itemW / 2 - minX
        topMargin = c.second - button / 2 - minY
      })
      if (ValueAnimator.areAnimatorsEnabled()) {
        // Fan out from the bubble.
        cell.translationX = (cx - c.first).toFloat()
        cell.translationY = (cy - c.second).toFloat()
        cell.alpha = 0f
        cell.animate().translationX(0f).translationY(0f).alpha(1f).setDuration(180).start()
      }
    }
    val p = WindowManager.LayoutParams(
      maxX - minX, maxY - minY,
      WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
      PixelFormat.TRANSLUCENT,
    ).apply {
      gravity = Gravity.TOP or Gravity.START
      x = minX
      y = minY
      title = "Hermes call controls"
    }
    try {
      wm.addView(root, p)
      menu = root
      menuKey = contentKey()
      main.removeCallbacks(dim)
      setDimmed(false)
    } catch (_: Exception) { }
  }

  /** One control: a round icon button with a small caption, read by TalkBack as one button. */
  private fun menuItem(item: Item): View = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    gravity = Gravity.CENTER_HORIZONTAL
    isClickable = true
    isFocusable = true
    contentDescription = item.label
    setOnClickListener { item.run() }
    ViewCompat.setAccessibilityDelegate(this, object : AccessibilityDelegateCompat() {
      override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
        super.onInitializeAccessibilityNodeInfo(host, info)
        info.className = Button::class.java.name
      }
    })
    addView(FrameLayout(context).apply {
      background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(item.fill); setStroke(dp(1), 0x59E9AE5B) }
      val ripple = TypedValue().also { context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, it, true) }
      if (ripple.resourceId != 0) foreground = context.getDrawable(ripple.resourceId)
      addView(ImageView(context).apply { setImageResource(item.icon); setColorFilter(item.tint) },
        FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
      importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }, LinearLayout.LayoutParams(button, button))
    addView(TextView(context).apply {
      text = item.label
      setTextColor(0xFFF1EADF.toInt())
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
      gravity = Gravity.CENTER
      maxLines = 1
      setShadowLayer(dp(3).toFloat(), 0f, 0f, 0xFF000000.toInt()) // Readable over any app.
      importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
  }

  private fun closeMenu() {
    menu?.let { runCatching { wm.removeViewImmediate(it) } }
    menu = null
    menuKey = null
  }

  // ------------------------------------------------------------------ actions

  /** Start the consent activity while the bubble is still visible (a visible overlay is what lets us open it). */
  private fun openConsent(id: String) {
    context.startActivity(Intent(context, ScreenConsentActivity::class.java)
      .putExtra("request_id", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    closeMenu()
  }

  private fun review() {
    val request = ScreenRequests.pending.value ?: return
    if (!request.fromUser) openConsent(request.id)
  }

  private fun shareScreen() {
    val id = ScreenRequests.requestFromUser(context) ?: return
    openConsent(id)
  }

  private fun toggleMute() {
    TalkState.muted.value = !TalkState.muted.value
  }

  private fun openApp() {
    closeMenu()
    context.startActivity(Intent(context, MainActivity::class.java)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
  }

  private fun endCall() {
    closeMenu()
    TalkService.stop(context)
  }
}
