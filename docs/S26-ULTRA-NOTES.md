# Galaxy S26 Ultra notes

What a third-party app can and cannot reach on Samsung phones, and how UltraX 26 handles it.

## What we read from the phone (nothing is hard-coded)
- Camera IDs, physical lens IDs, sensor size, focal lengths, apertures, zoom range, ISO / exposure
  ranges, minimum focus distance, output sizes (incl. 7680×4320), frame-rate ranges, high-speed
  sizes/ranges, dynamic-range profiles (HLG10 / HDR10 / HDR10+ / Dolby Vision), stream use cases,
  every available request/result/session key — all from `CameraCharacteristics`.
- Encoders and their limits (8K sizes, 10-bit profiles, APV availability, bitrate ranges) from
  `MediaCodecList`.
- Audio input devices (built-in mics, Bluetooth, USB) from `AudioManager`.

Run **Settings → Diagnostics → Device report** on your S26 Ultra to see exactly what it exposes,
and share it if something looks off.

## Samsung specifics
- **Hidden per-lens cameras.** Samsung publishes only the logical rear camera ("0") and the front
  camera ("1") in `getCameraIdList()`. The individual sensors (ultra-wide, 3×, 5× periscope) usually
  answer to hidden numeric IDs (20, 21, 23, 50, 52, 54 … vary by model). *Settings → Camera → Probe
  hidden camera IDs* scans 0–99 and lists whatever opens. Hidden IDs give you the raw sensor stream
  of one lens (useful for locking the 5× periscope without Samsung's automatic lens switching), but
  some hidden IDs cannot be opened by third-party apps — the app reports the error and falls back.
- **Physical camera IDs behind the logical camera** are often not listed for third-party apps, so
  *Lock to a physical camera* may be unavailable; zoom presets (which lens Samsung picks is then
  automatic at 0.6× / 1× / 3× / 5×) or hidden IDs are the alternatives.
- **Vendor tags.** Samsung's own camera uses `samsung.android.*` vendor keys (Pro-video Log gamma,
  audio zoom, Super Steady, HDR10+ toggles, etc.). If the HAL lists any of them in
  `getAvailableCaptureRequestKeys()`, they appear in **All camera keys** and can be set with a typed
  value. If they are not listed, they are not reachable from any third-party app.
- **Log video.** Samsung Log is a vendor feature. The closest portable equivalent is
  *Tone → Flat / log-like* (a lifted-shadow contrast curve via `TONEMAP_CURVE`) with a LUT in post;
  combine with HLG10 for the most latitude.
- **8K.** Expect 8K at 24/30 fps via HEVC (and possibly AV1/APV depending on firmware), heavy
  thermals and ~80–100 Mb/s auto bitrate. The thermal chip shows headroom; enable *Downgrade on
  severe* to drop bitrate automatically or *Stop on critical*.
- **4K120 / 1080p240 high-speed** are constrained high-speed sessions: only preview + recording
  surfaces are allowed, so the gesture camera stream is unavailable during high-speed capture
  (audio, voice and device triggers still work). Slow-motion muxing is available.
- **Concurrent front + rear streaming** (gesture camera ≠ recording camera) depends on
  `CameraManager.getConcurrentCameraIds()`; the app falls back to the recording camera when the
  phone does not offer the pair.
- **HDR previews.** The preview and recording use the same dynamic-range profile; the analysis stream
  switches to 10-bit P010 automatically when the profile does not allow an 8-bit companion stream.
- **Microphone concurrency.** Android lets one privacy-sensitive client capture at a time. The
  system speech recognizer may therefore go silent while recording; the built-in trained keyword
  spotter does not have this problem because it listens to the app's own audio stream.

## Not implemented by design
- Samsung "Director's View", Single Take, AI zoom, 100× Space Zoom UI, and any feature that lives in
  Samsung's camera app rather than the Camera2 HAL.
- Background recording with the screen off (the camera stream is torn down when the preview
  surface disappears); the foreground service only keeps the process alive when you switch apps.

## 8K video and Camera2

Samsung's own camera app records 8K through private HAL paths. For third-party apps the Camera2
`StreamConfigurationMap` decides what exists. On recent Ultras 7680×4320 is often **not** in the regular
output list of the public logical camera (ID 0); where it is exposed at all it tends to show up in one of
three places, all of which the app now checks:

1. the **high-resolution** PRIVATE output set (`getHighResolutionOutputSizes`) — documented as "may run
   below 20 fps", the app labels these sizes *high-res mode* and caps the frame rate accordingly;
2. a **hidden camera ID** (the per-sensor cameras Samsung does not list publicly);
3. a **physical sub-camera** of the logical camera, reachable by recording with a physical-lens lock.

*Settings ▸ Video ▸ Scan all cameras for 8K* runs this search and switches the camera for you. If the scan
finds nothing, the phone does not expose 8K to third-party apps and no setting in this app can change that;
share the Diagnostics report so the vendor tags can be checked.
