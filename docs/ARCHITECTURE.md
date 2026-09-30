# Architecture

Single-module Android app, Kotlin + Jetpack Compose, no third-party camera or media frameworks.

```
app/src/main/kotlin/com/ultrax26/recorder/
├─ UltraXApp.kt / AppGraph.kt        Application + manual DI graph (all singletons, settings flow wiring)
├─ settings/                         AppSettings (every option, kotlinx.serialization) + SettingsStore (JSON, presets)
├─ camera/
│   ├─ CameraCatalog.kt              camera IDs incl. hidden Samsung IDs, per-camera CameraInfo summary
│   ├─ Capabilities.kt               device-filtered option lists, sizes/fps/HDR/high-speed helpers
│   ├─ RequestApplier.kt             CaptureSettings → CaptureRequest keys (only advertised keys), custom/vendor keys
│   ├─ GenericKeyCodec.kt            text ⇄ typed values for any Camera2 key
│   ├─ RegionMapper.kt / Tonemaps.kt metering regions, tonemap presets
│   ├─ SessionPlan.kt                declarative description of a session (streams, profile, fps, lens lock)
│   ├─ CameraEngine.kt               CameraDevice/Session lifecycle, repeating requests, zoom ramps, tap AF, results
│   └─ ThermalMonitor.kt
├─ recording/
│   ├─ EncoderCapabilities.kt        MediaCodecList discovery (8K, 10-bit profiles, APV, bitrate ranges)
│   ├─ VideoEncoder.kt               persistent surface-input encoder (HDR color info, key-frame requests)
│   ├─ AudioCapture.kt               one AudioRecord (BOOTTIME timestamps, gain/HPF/limiter) fanned out to listeners
│   ├─ AudioEncoder.kt               async AAC encoder fed from PCM chunks
│   ├─ ClipWriter.kt                 MediaMuxer segments, pause compression, slow-mo/time-lapse time scaling
│   ├─ PreRollBuffer.kt              RAM ring of encoded samples, keyframe-aligned drain
│   ├─ StorageTarget.kt              MediaStore / SAF / private files, filename templates
│   ├─ RecordingController.kt        builds sessions from settings, state machine, executes trigger actions
│   └─ RecordingService.kt           foreground service (camera|microphone) + notification actions
├─ triggers/
│   ├─ Model.kt                      Trigger (sealed, serializable), TriggerEvent, TriggerRule, RecAction, RecState
│   ├─ TriggerEngine.kt              rule matching (hold/burst/sequence), cooldowns, countdown, log
│   ├─ audio/                        Dsp (FFT, mel, DCT), TransientDetector, WhistleDetector, KeywordSpotter (MFCC+DTW),
│   │                                AudioTriggerHub (worker thread over the PCM stream), SystemSpeechRecognizer
│   ├─ vision/                       YuvToRgb, FrameDispatcher, Hand/FaceGestureInterpreter, ScopesAnalyzer,
│   │                                MediaPipeHandDetector, MlKitFaceDetector
│   └─ device/                       volume keys, MediaSession buttons, shake, proximity
├─ effects/
│   ├─ EffectsModel.kt / EffectCatalog.kt   settings (stickers, background, beauty, face/age/fun/look) + built-in catalog & looks
│   ├─ FaceGeometry.kt / MeshTypes.kt       scene mapping, face frame & head rotation, sticker projection, detector interfaces
│   ├─ EffectsRenderer.kt                   GL thread: camera SurfaceTexture → composite/face/style/sticker passes → preview + encoder
│   ├─ BackgroundSources.kt / StickerTextures.kt   photo/video/parallax backgrounds, drawable & PNG textures
│   ├─ gl/ (EglCore, GlUtil, Shaders)       EGL context, programs/FBOs/quads, GLSL sources
│   └─ ml/MediaPipeEffects.kt               Face Landmarker, Image Segmenter, Pose Landmarker adapters
├─ feedback/Feedback.kt              haptics, tones, TTS, screen flash
├─ diagnostics/CameraReport.kt       shareable device report
└─ ui/                               Compose: MainActivity (nav, permissions), camera screen + overlays,
                                     settings tabs, triggers editor + keyword enrollment, all-keys editor, diagnostics
```

## Threads
- `ux-camera`: Camera2 callbacks, request building.
- `ux-analysis`: ImageReader → YUV→RGB → MediaPipe/ML Kit → interpreters → engine (12 fps, throttled).
- `ux-recorder`: MediaCodec callbacks, muxing, controller state.
- `ux-audio`: AudioRecord read loop (urgent priority); `ux-audio-triggers`: detectors.
- `ux-trigger-engine`: single-threaded rule evaluation and countdown timer.
- Main thread: Compose UI, SpeechRecognizer.

## Key design decisions
- **One persistent video encoder per camera session.** The camera only targets the encoder surface
  while recording or pre-rolling, so clips start on the next frame and pre-roll costs no session
  rebuild. Stopping never sends EOS; the writer waits for the next keyframe.
- **One AudioRecord.** The AAC encoder and every audio trigger consume the same PCM, avoiding
  Android's single-client microphone arbitration while recording.
- **Timestamps in CLOCK_BOOTTIME.** Camera sensor timestamps (REALTIME source) and
  `AudioRecord.getTimestamp(TIMEBASE_BOOTTIME)` share a clock; the writer subtracts the clip start and
  accumulated pause time.
- **Settings are the single source of truth.** The UI writes `AppSettings`; the graph fans changes out
  to the engine (capture keys), the controller (session rebuild when a stream-affecting key changes),
  the trigger engine, detectors and feedback.
- **Only what the HAL advertises.** Option lists and request keys are read from
  `CameraCharacteristics`; unsupported keys are skipped and reported, never guessed.

## Testing
`app/src/test` has JVM tests for the FFT/DSP, clap & whistle detectors on synthetic audio, the
keyword spotter (enroll + recognize synthetic words), the trigger engine (counts, holds, cooldowns,
states, sequences, voice matching), the face/hand interpreters (blink bursts, nods, holds, finger
counting), settings serialization, pre-roll draining and filename templating. Camera/encoder paths
need a device.
