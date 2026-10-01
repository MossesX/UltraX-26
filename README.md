# UltraX 26

A professional video recorder for the **Samsung Galaxy S26 Ultra** (and other recent Android
phones) built directly on Camera2 + MediaCodec, with **hands-free control**: start, pause and stop
a take with a **thumbs-up, a fist, a double clap, three fast blinks, a nod, a whistle or your voice**.
It also makes **video calls the Meet way**: share a link and the other person joins from any browser,
no app needed — with your filters and backgrounds applied to the call.

> Status: first build. Every feature below is implemented in code; the project is compiled in CI
> (GitHub Actions → APK artifact) but has **not yet been exercised on a physical S26 Ultra**.
> Expect to tune thresholds in *Settings → Triggers* for your voice, lighting and distance.

## Highlights

**Recording**
- 8K (7680×4320), 4K, 1440p, 1080p, 720p — every size the camera HAL advertises, at every frame
  rate it supports (24/25/30/48/50/60 and high-speed 120/240 fps where offered).
- HEVC (H.265), H.264, AV1 and APV (Android 16 "Advanced Professional Video") — whichever encoders
  the SoC ships; 10-bit HDR (HLG10, HDR10, HDR10+, Dolby Vision OEM profile) when both the camera
  and the encoder support it.
- Bitrate modes VBR/CBR/CQ up to the encoder's maximum, I-frame interval, B-frames, profile/level,
  full/limited range, container (MP4/WebM).
- **Pre-roll**: keep the last N seconds in RAM while armed so a gesture-started clip begins *before*
  the gesture.
- Segmenting by minutes or file size (keyframe aligned), pause/resume with timestamp compression,
  chapter markers, max duration, thermal auto-stop/auto-downgrade, low-storage guard.
- Slow motion (high-speed capture muxed for slow playback), time-lapse (camera-side frame
  decimation) and long-interval capture.
- Audio from any input (built-in, Bluetooth, USB) at 44.1/48/96 kHz, mono/stereo, 64–512 kb/s AAC,
  digital gain, wind high-pass filter, limiter, privacy-sensitive flag; lossless WAV sidecar option.
- Frame grabs (JPEG) during recording; per-clip JSON sidecar with settings, markers and trigger log.

- **Pinch zoom by hand**: spread thumb and index finger to zoom in, pinch to zoom out; optional live
  continuous zoom while holding the pinch.
- **Commands tab**: every setting, function and action — set resolution, pick a camera, zoom to 5×,
  change ISO, apply a look, mute audio… — in one searchable list, each bindable to a recorded voice
  phrase or any gesture.
- **Record your own voice triggers** — any word or sound, trained on your voice in the app — and
  **remove trigger sounds from recordings** (claps, commands, the confirmation beep are silenced before
  encoding, video untouched).

**Camera control ("every option the phone has")**
- Manual ISO / shutter (180° helper) / EV / AE lock / anti-banding / target FPS range.
- Manual focus in diopters with distance readout, AF modes, tap-to-focus/meter, AF lock,
  **rack focus A→B** with configurable duration.
- Manual white balance in Kelvin + tint (or any AWB preset), AWB lock, manual RGGB gains / color
  matrix.
- Lens selection: zoom presets from the physical lenses (0.6× · 1× · 3× · 5× …), smooth cinematic
  zoom ramps, pinch zoom, physical-camera lock where the OEM exposes physical IDs, and **hidden
  camera ID probing** (Samsung hides per-lens sensors behind IDs like 20/21/23/50…).
- OIS / EIS / preview stabilization, noise reduction, edge, tone mapping (device, sRGB, Rec.709,
  linear, flat/log-like for grading, gamma, custom curve), distortion correction, hot-pixel,
  shading, scene & effect modes, extended scene (bokeh), face detection, capture intent, black-level
  lock, zoom settings override, autoframing, aperture / ND filter / focal length (if present),
  sensor test patterns, statistics toggles.
- **All camera keys**: a searchable editor over *every* `CaptureRequest` key the HAL advertises —
  including Samsung vendor tags (`samsung.android.*`) — with typed value entry, plus a live view of
  every `CaptureResult` key.

**Hands-free triggers (the point of the app)**
- **Hand gestures** (MediaPipe): thumbs up/down, open palm, fist, victory, pointing up, ILY; derived
  OK-sign, pinch, rock-on, call-me, finger counts (1–5); wave; both-hands-up; visual clap;
  gesture *sequences* (e.g. palm → fist).
- **Face**: blink ×N fast, wink (left/right), smile hold, mouth open, head nod ×N, head shake ×N,
  head tilt, subject enters / leaves frame.
- **Audio**: clap ×N, finger snap ×N, whistle, loud sound.
- **Voice**: system speech recognizer (free-form, on-device) **and** a built-in speaker-trained
  keyword spotter that runs on the recording's own microphone stream, so voice control keeps
  working while recording.
- **Device**: volume keys (short/long), Bluetooth remote/headset buttons, shake, proximity wave,
  timer.
- Rules map any trigger to any action (start, stop, pause, resume, toggle, snapshot, marker, arm,
  disarm, cancel countdown, next/prev lens, zoom in/out, torch, AE/AF lock) with per-rule cooldown,
  hold duration, allowed recorder states, optional countdown with beeps, and haptic / tone / flash /
  spoken confirmation.
- A gesture camera can be the recording camera or (on phones that stream concurrently) the other
  camera — e.g. record with the rear lens and control with the front camera.

**Effects (AR filters)**
- 46 one-tap looks (Cop, Wizard, Alien, Astronaut, Pirate, Zombie, Vampire, Robot, Superhero, Cat, Dog,
  Older, Younger, Baby face, Glam, Vintage, Cyberpunk…), 72 stackable original stickers anchored to the
  face mesh or body pose (hats, glasses, mustaches, noses, masks, costume pieces, wings and capes drawn
  *behind* you), 16 animated backgrounds, 6 head-tracked 3D parallax scenes, blur / color / your own
  photos and videos, beauty sliders (smoothing, eyes, face slim, lipstick, teeth…), face modes, stylized
  age looks, fun distortions and color looks. Import any PNG as a sticker. See **docs/EFFECTS.md**.

**Monitoring**
- Grids (thirds, golden, center, diagonals, square), aspect guides (2.39, 1.85, 16:9, 4:3, 1:1,
  9:16, 4:5), safe areas, electronic level, histogram, RGB-parade style waveform, zebras, focus
  peaking, false color, audio meter, timecode, gesture HUD (hand skeletons, eye state, blink
  counter, heard phrases), thermal headroom, storage estimate.
- Diagnostics: a full device report (every camera characteristic, encoder capability and audio
  input) you can share.

See **docs/FEATURES.md** for the complete matrix, **docs/GESTURES-AND-VOICE.md** for how the
triggers work and how to tune them, **docs/ARCHITECTURE.md** for the code layout and
**docs/S26-ULTRA-NOTES.md** for what is and isn't reachable from a third-party app on Samsung.

**Video calls (`docs/CALLS.md`)**
- **Call anyone, no app required**: tap *Call → Create call link*, share it by SMS / any messenger /
  QR code; the other person opens it in a browser (phone or computer) and is in the call.
- Fixed personal address (`ux-yourname`) so people can call you whenever the app is open; UltraX
  phones call each other directly; incoming calls ring with Answer / Decline — or say **“answer”**.
- Group calls up to 6 (peer-to-peer mesh), text chat, speaker/earpiece, camera on/off, mute,
  **record while in a call**, and every AR effect / virtual background applied to your call video.
- **Call with Google Meet** hand-off: start a Meet call, join a Meet link or code, or Meet-video-call
  a number / contact from the same sheet (runs in the Meet app — Google has no API for third-party
  clients to join Meet with their own video).
- WebRTC media (DTLS-SRTP encrypted, hardware codecs) with PeerJS-protocol signaling. Works out of
  the box on free public servers; `server/` has a one-command self-hosted stack (peerjs-server +
  coturn + Caddy). The browser client lives in `web/call/` and deploys to GitHub Pages automatically.

## Build

Requirements: Android Studio Ladybug or newer (AGP 8.10, Kotlin 2.1), JDK 17, Android SDK 36.

```bash
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # DSP, detector, keyword-spotter, trigger-engine unit tests
```

The MediaPipe models (gesture recognizer, face landmarker, selfie segmenters, pose landmarker; ~35 MB
total) are downloaded by Gradle at build time into `app/build/generated/ultrax-assets/` and verified by
SHA-256, so they are not committed. Built-in sticker art is generated by `tools/gen_assets.py`.

CI: `.github/workflows/android-build.yml` runs the unit tests and uploads debug + release APKs as
the **UltraX26-apks** artifact on every push. `.github/workflows/pages.yml` publishes the browser
call client (`web/`) to GitHub Pages — enable *Settings → Pages → Source: GitHub Actions* once.

### Install on the phone
Easiest: open **https://github.com/MossesX/UltraX-26/releases/latest/download/UltraX26-debug.apk** in the
phone's browser, allow installs from the browser when asked, tap Install. CI republishes that file on every
push to `main`. Alternatives (Android Studio, `adb install -r app/build/outputs/apk/debug/app-debug.apk`)
are in **docs/INSTALL.md**.

## Permissions
Camera, microphone (recording + audio triggers + calls), notifications (foreground recording / call
service), internet (video calls only — recording and all detection stay on the phone).
Location is optional (geotagging) and off by default.

## License
Copyright © 2026. All rights reserved by the repository owner unless a LICENSE file says otherwise.
Third-party components: MediaPipe Tasks (Apache-2.0), ML Kit face detection (Google APIs ToS),
AndroidX / Jetpack Compose (Apache-2.0), libwebrtc via `io.github.webrtc-sdk` (BSD-3), OkHttp
(Apache-2.0), ZXing (Apache-2.0), PeerJS browser library (MIT, vendored in `web/call/vendor/`).
