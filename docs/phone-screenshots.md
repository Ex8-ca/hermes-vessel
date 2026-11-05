# One-shot screenshots

Settings → Phone access → **Allow Hermes to request screenshots** is off by default and scoped to the selected gateway. Removing that gateway clears its screenshot permission.

During a connected call, Hermes can request one screenshot with a reason. The phone enforces the setting independently of which tools the server advertises. The user reviews the request and Android presents a fresh MediaProjection consent prompt every time. In the background, a notification opens the review; the app does not automatically launch an activity over other apps. If notifications are disabled, return to Vessel to review the request.

After approval, a separate mediaProjection foreground service acquires one image and immediately releases its projection before compression/transmission. Consent is never persisted or reused. Cancellation, revocation, call termination, timeout, and service destruction stop access. Images stay in memory on the phone and are sent to the selected gateway, which forwards the image to its realtime AI provider. Provider retention policies apply. Secure/protected screens may be blank or unavailable.

The Android prompt may say “recording or casting”; Vessel requests no playback-audio capture and stops after one screen image. The user may choose one app or the entire display where supported by Android.

## Verification

- `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --no-daemon assembleDebug lintDebug` passes (lint warnings remain).
- `python -m unittest discover -s tests -p test_screen_protocol.py -v`: six protocol tests, including default-deny, duplicate requests, declined/revoked/invalid results, forwarding, and cancellation cleanup.
- Standard Android 35 AOSP emulator: actual image captured over websocket after system approval; image visually checked. `dumpsys media_projection` returns `null` after capture. Next request displays a fresh system consent dialog; cancelling sends only an error. Switching toggle off rejects subsequent requests. Second gateway starts disabled.
- ATD emulator does not produce MediaProjection frames: verified timeout cleanup instead. Use the standard image, not ATD, for screenshot success tests.
- Real Realtime model on isolated Vessel port: requested screenshot, consumed the actual emulator image, and correctly described its UI. No user's phone contents used.
- Physical phone and OEM-specific behavior still require user testing.

Camera, location, motion, clipboard and torch are separate follow-up capabilities, not implemented by this screenshot change. SMS and notification reading remain excluded.
