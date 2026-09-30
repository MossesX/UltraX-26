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
├─ calls/
│   ├─ CallSettings.kt / InviteLinks.kt     settings (signaling, ICE, quality) and shareable link build/parse
│   ├─ PeerJsSignaling.kt                   PeerJS-protocol websocket client (OkHttp) + payload codec
│   ├─ WebRtcCore.kt                        PeerConnectionFactory, audio module, local tracks; renderer → SurfaceTextureHelper video source
│   ├─ PeerLink.kt                          one RTCPeerConnection per media / data connection (PeerJS semantics)
│   └─ CallManager.kt                       host/join/ring/answer state machine, mesh roster, chat, audio routing, foreground service
├─ feedback/Feedback.kt              haptics, tones, TTS, screen flash
├─ diagnostics/CameraReport.kt       shareable device report
└─ ui/                               Compose: MainActivity (nav, permissions, call links), camera screen + overlays,
                                     call overlay / invite sheet (ui/call), settings tabs, triggers editor + keyword
                                     enrollment, all-keys editor, diagnostics
```

Outside the app module: `web/call/` (browser call client, PeerJS + vanilla JS, deployed by
`.github/workflows/pages.yml`) and `server/` (self-hosted signaling + TURN + HTTPS bundle).

## Video-call data flow

```
camera → EffectsRenderer ──► preview surface
                          ├─► encoder surface (recording)
                          └─► call surface → SurfaceTextureHelper → VideoSource → VideoTrack ─┐
AudioRecord(VOICE_COMMUNICATION, HW AEC/NS) → JavaAudioDeviceModule → AudioTrack ─────────────┤
                                                                                              ▼
              PeerJsSignaling ◄── OFFER/ANSWER/CANDIDATE JSON ──► PeerLink (RTCPeerConnection per peer)
                                                                        │ remote VideoTrack → SurfaceViewRenderer tile
                                                                        └ DataChannel (JSON): name / roster / chat / bye
```

`CallManager` owns the state machine (`IDLE → CONNECTING → READY → RINGING_IN|RINGING_OUT → IN_CALL`),
one `PeerLink` per remote peer for media and (host↔guest) one for data. Group calls are a mesh: the
host answers a newcomer, then sends it the current roster over the data link; the newcomer dials each
listed peer with the room key so they auto-answer. The same protocol is implemented by the browser
client, so phones and browsers mix freely in one call. The recording controller keeps the GL pipeline
alive while a call is active (`setCallActive`) even when no effect is selected.

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
