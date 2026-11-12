# SplendorCam

Camera app for low-end devices that brings an iPhone-level photo experience,
so users keep their device instead of buying an iPhone. Free-code stack only
(Camera2, no paid tools).

## Design (PROVEN by the Camera Probe on the Redmi 15C, 2026-11-12)

- **RAW pipeline.** Both cameras are LEVEL_3 with RAW + manual sensor + burst.
- Back camera: RAW_SENSOR 4080x3072 (12.5MP), ISO 50–16000, exposure
  0.1ms–0.4s, f/1.8, full AF, flash. Front: RAW 3264x2448 (8MP),
  ISO 50–8000, f/2.0, fixed focus.
- Camera2 JPEG caps at 2560x1920 on the back camera — RAW is the high-res path.
  The full 50MP mode is not exposed via Camera2.
- A RAW frame is ~25MB: bursts stream through memory one frame at a time,
  never held whole, on 4GB RAM. Max exposure is 0.4s/frame, so night mode is
  stacking many short frames, not one long exposure. No OIS, no hardware
  depth output (portrait bokeh comes from ML segmentation, later).

## v1 — what this scaffold does

- Live preview, back/front camera switch.
- Shutter captures a 5-frame RAW burst and saves each frame as a DNG file
  in `Pictures/SplendorCam` (visible in the gallery / file manager).
- Manual mode: ISO and exposure-time sliders (ranges read from the camera
  at runtime); Auto mode uses the camera's own auto-exposure.
- Plain `android.app.Activity`, no AndroidX, no dependencies — the build
  needs only the Android SDK + the two Gradle plugins.

## Build (Termux)

```
./gradlew :app:assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. Install with
`adb install -r app/build/outputs/apk/debug/app-debug.apk` or copy the APK
to the phone and install it there.

## What's next (later rounds, not v1)

Multi-frame merge + tone mapping (the iPhone look), night stacking,
portrait mode via ML segmentation, proper preview aspect handling.
On-device camera capture is UNVERIFIED until the APK is run on the phone.
