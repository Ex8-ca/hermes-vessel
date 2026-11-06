# One-shot screenshots

Settings → Phone access → **Allow Hermes to request screenshots** is off by default and scoped to the selected gateway. Removing that gateway clears its screenshot permission.

During a connected call, Hermes can request one screenshot with a reason. The phone enforces the setting independently of which tools the server advertises. The user reviews the request and Android presents a fresh MediaProjection consent prompt every time. In the background, a notification (or the floating call button, below) opens the review; the app never launches an activity over other apps on its own. If notifications are disabled and the call button is off, return to Vessel to review the request. The review opens in its own task over whatever app you were using, so the screen that gets captured is that app, not Vessel. While it is open the review hides every overlay window (`setHideOverlayWindows`), ours and any other app's, so nothing can be drawn over the consent.

After approval, a separate mediaProjection foreground service acquires one image and immediately releases its projection before compression/transmission. Consent is never persisted or reused. Cancellation, revocation, call termination, timeout, and service destruction stop access. Images stay in memory on the phone and are sent to the selected gateway, which forwards the image to its realtime AI provider. Provider retention policies apply. Secure/protected screens may be blank or unavailable.

The Android prompt may say “recording or casting”; Vessel requests no playback-audio capture and stops after one screen image. The user may choose one app or the entire display where supported by Android.

## Floating call button

Settings → Phone access → **Floating call button**, off by default. Turning it on opens Android's "Display over other apps" page; the button appears only after you grant it there.

- **When it shows:** only during a call, only while Vessel is not on screen. It lives inside the call's existing foreground service (no extra service, wakelock or polling) and is removed when the call ends.
- **What it does:** tap it and four controls fan out: **Show screen** (you choose to share one screenshot; Android's consent prompt still runs every time), **Mute/Unmute**, **Open Vessel**, **End call**. When Hermes asks for a screenshot, the button gets a coral dot and pulses briefly; tapping it opens the review.
- **Moving and hiding:** drag it anywhere; it snaps to the nearest side edge. Drag it onto the ✕ at the bottom to hide it until you next leave Vessel. It dims after a few seconds alone.
- **Never in the picture:** it hides from the moment Android's consent prompt opens until the capture finishes, so screenshots never include it.
- **Overlay rules:** `TYPE_APPLICATION_OVERLAY`, never focusable, every window only as large as what it draws. The ✕ drop target is the only pass-through window and uses `FLAG_NOT_TOUCHABLE` with alpha 0.8, the maximum Android 12+ allows for overlays that let touches through.
- **Accessibility:** 56dp button and 52dp controls with labels, a live description when Hermes asks for the screen, and every control (including "Move to the other side" and "Hide for now") is also a TalkBack action on the button itself, so nothing requires dragging. Animations are skipped when the system's animations are off.
- **User-chosen screenshots** are sent as `{"type": "user_screenshot", "image": ...}`. The plugin checks the image the same way as a requested one, adds it to the conversation as untrusted visual data and lets Hermes answer. They do not depend on the "Hermes may ask" switch: you are the one asking, and Android asks you to confirm.

## Verification

- `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --no-daemon assembleDebug lintDebug` passes (lint warnings remain).
- `python -m unittest discover -s tests -p test_screen_protocol.py -v`: six protocol tests, including default-deny, duplicate requests, declined/revoked/invalid results, forwarding, and cancellation cleanup.
- Standard Android 35 AOSP emulator: actual image captured over websocket after system approval; image visually checked. `dumpsys media_projection` returns `null` after capture. Next request displays a fresh system consent dialog; cancelling sends only an error. Switching toggle off rejects subsequent requests. Second gateway starts disabled.
- ATD emulator does not produce MediaProjection frames: verified timeout cleanup instead. Use the standard image, not ATD, for screenshot success tests.
- Real Realtime model on isolated Vessel port: requested screenshot, consumed the actual emulator image, and correctly described its UI. No user's phone contents used.
- Floating call button (standard Android 35 emulator): Settings switch opens the system overlay page; after granting, the button appears over the home screen during a call and is absent while Vessel is in front. Show screen → system consent → captured image is the clean home screen (no button, menu, dialog or Vessel UI) and the projection is released. A Hermes request while on the home screen shows the coral dot; tapping opens the review over the home screen with the button hidden; approving captures the home screen. Drag onto ✕ hides it, leaving and re-entering Vessel brings it back, and End call from the menu removes every overlay window and stops the call service.
- Physical phone and OEM-specific behavior still require user testing (TalkBack actions were not exercised with a screen reader).

Camera, location, motion, clipboard and torch are separate follow-up capabilities, not implemented by this screenshot change. SMS and notification reading remain excluded.
