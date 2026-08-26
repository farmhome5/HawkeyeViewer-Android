# Handoff: NOVATEK "HDUSB" borescope fix — fresh native libs + descriptor hardening

**For Claude Code or Pamela.** Cowork session 2026-08-25 already did the file surgery
(steps marked DONE). What remains is a normal app rebuild + on-camera test.

## Background (one paragraph)

The NOVATEK "HDUSB" borescope cameras (VID 0x2622 / PID 0xAB01) put their UVC
format descriptors AFTER the bulk endpoint descriptor. The app's shipped
`libusb100.so` inside `libausbc/libs/libuvc-3.2.9.aar` was a stale prebuilt whose
parser drops those trailing descriptors, so libuvc found zero formats ->
"open camera failed, preview size unsupported" -> black screen. The 3.3.3 source
in this repo is correct; a fresh build of all four libs has existed in
`AndroidUSBCamera-3.3.3/AndroidUSBCamera-3.3.3/libuvc/src/main/libs/` since
Aug 18, but earlier sessions only ever injected libuvc.so/libUVCCamera.so into
the AAR — never libusb100.so. Full diagnostic: project doc
`claude/findings - LTC camera USB diagnostic 2026-08-25.md` (claude.ai project
"Android Hawkeye Viewer").

## Already DONE (2026-08-25, via Cowork folder connection)

1. All four fresh `.so` (arm64-v8a + armeabi-v7a) injected into
   `libausbc/libs/libuvc-3.2.9.aar`. Backup: `libuvc-3.2.9.aar.bak-20260825`.
2. `device.c` hardening patch applied to
   `AndroidUSBCamera-3.3.3/AndroidUSBCamera-3.3.3/libuvc/src/main/jni/libuvc/src/device.c`
   (uvc_scan_streaming now scans ALL endpoints' extras and LOGWs which source it
   used). NOTE: the patched code is NOT in the injected .so yet — the injected
   libusb100.so fix alone should make the camera work; the patch lands next
   rebuild (Step B).
3. `tools/inject_fresh_so.py` added (re-runs the AAR injection after any ndk-build).

## Step A — Rebuild the app and test (do this now)

```cmd
gradlew.bat :app:assembleMobileDebug
adb install -r app\build\outputs\apk\mobile\debug\app-mobile-debug.apk
```

(Or Android Studio: Build > Clean Project, then Run. If Gradle caches the old
AAR, add `--refresh-dependencies` or delete `.gradle/` in the project.)

**Test:** plug in the NOVATEK camera, PRESS ITS POWER BUTTON, launch the app.

- Success: live video; supported-size logging from CameraUVC shows
  1280x720 (default), 640x480, 320x240 @30fps MJPEG; no
  "open camera failed, preview size unsupported" in logcat.
- Watch: `adb logcat | findstr /i "uvc_scan_streaming CameraUVC preview"`
- Frame intervals are continuous (dwFrameIntervalStep=0) on this camera —
  if preview opens but fps is odd, capture logcat for the next session.

## Step B — Optional next iteration: rebuild natives with the hardening patch

NDK is already installed (26.1.10909125 / 26.3.11579264 / 27.0.12077973 under
`%LOCALAPPDATA%\Android\Sdk\ndk`). From the project root:

```cmd
%LOCALAPPDATA%\Android\Sdk\ndk\27.0.12077973\ndk-build.cmd -j8 -C AndroidUSBCamera-3.3.3\AndroidUSBCamera-3.3.3\libuvc\src\main
python tools\inject_fresh_so.py
gradlew.bat :app:assembleMobileDebug
```

After this, logcat additionally shows which descriptor source libuvc used, e.g.
`uvc_scan_streaming: interface 1 has no interface extra data, using 139 bytes of
class-specific data from endpoint[0] (bEndpointAddress=0x81)`.

## Rollback

- AAR: restore `libausbc/libs/libuvc-3.2.9.aar.bak-20260825`.
- device.c: `git checkout -- AndroidUSBCamera-3.3.3/` (patch is uncommitted).

## Phone app note

The same stale-libusb100 lineage affects HawkeyeViewerMask (com.hawkeye.viewer,
separate Capacitor project). The four fresh .so in
`AndroidUSBCamera-3.3.3/AndroidUSBCamera-3.3.3/libuvc/src/main/libs/` are the fix
there too — connect that project folder in a Cowork session (or point Claude Code
at it) to repeat the swap.
