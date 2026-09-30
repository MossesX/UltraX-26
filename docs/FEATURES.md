# Feature matrix

Legend: ✅ implemented · 🧪 implemented, needs on-device tuning · ⛔ not possible for a third-party
app (see S26-ULTRA-NOTES.md) · 🔜 planned

## Video
| Feature | Status | Where |
| --- | --- | --- |
| Every HAL-advertised size incl. 8K 7680×4320 | ✅ | Settings → Video → Resolution (list is read from `StreamConfigurationMap`) |
| Frame rates 24/25/30/48/50/60 (as advertised) | ✅ | Settings → Video → Frame rate |
| High-speed 120/240 fps (constrained high-speed session) | ✅ | Settings → Video → High speed |
| Slow-motion muxing (e.g. 240→30 fps) | ✅ | Settings → Video → Slow-motion playback fps |
| HEVC / H.264 / AV1 / APV encoders | ✅ | Settings → Video → Codec / Encoder (what `MediaCodecList` reports) |
| 10-bit HDR: HLG10, HDR10, HDR10+, Dolby Vision (OEM) | ✅ | Settings → Video → HDR (camera `DynamicRangeProfiles` × encoder profiles) |
| VBR / CBR / CQ, custom bitrate, I-frame, B-frames, profile, level | ✅ | Settings → Video |
| Pre-roll buffer (N s before the trigger) | ✅ | Settings → Video → Pre-roll |
| Segmenting by time / size (keyframe aligned) | ✅ | Settings → Video |
| Pause / resume without gaps | ✅ | Record controls, gestures, voice |
| Time-lapse (1 of N frames) & interval capture | ✅ | Settings → Video |
| Max duration, thermal stop / downgrade, storage guard | ✅ | Settings → Video |
| Orientation lock, mirror front camera | ✅ | Settings → Video |
| Chapter markers | ✅ | ⚑ button, voice "marker", any rule |
| Frame grab JPEG while recording | ✅ | 📷 button / victory gesture / voice |
| Per-clip JSON sidecar (settings, markers, trigger log) | ✅ | Settings → Storage |
| Gyro CSV sidecar for post-stabilization | 🔜 | setting exists, writer pending |
| Samsung Log / Pro-video "Log" gamma | ⛔ vendor | use *Tone → Flat/log-like* + LUT in post |
| APV 422 10-bit | ✅ where the SoC exposes `video/apv` | Codec list |

## Audio
| Feature | Status |
| --- | --- |
| Source (camcorder, mic, unprocessed, voice performance…) | ✅ |
| 44.1 / 48 / 96 kHz, mono / stereo, AAC 64–512 kb/s | ✅ |
| Bluetooth / USB microphone as input device | ✅ (`AudioRecord.setPreferredDevice`) |
| Digital gain, wind high-pass, limiter, mute | ✅ |
| Privacy-sensitive capture flag (concurrency behaviour) | ✅ |
| Lossless WAV sidecar | 🔜 (setting present) |
| Samsung audio zoom / 360 audio | ⛔ vendor |

## Camera controls
All standard `CaptureRequest` controls have dedicated UI (Settings → Camera and the on-screen
pro strip). Anything else — including every **Samsung vendor tag** the HAL lists in
`getAvailableCaptureRequestKeys()` — is reachable in **Settings → Camera → All camera keys**.

## Triggers
| Trigger | Engine | Status |
| --- | --- | --- |
| Thumbs up/down, open palm, fist, victory, pointing up, ILY | MediaPipe gesture recognizer | ✅ |
| OK sign, pinch, rock-on, call-me, finger count 1–5 | landmark geometry | 🧪 |
| Wave, hands up, visual clap, gesture sequence | landmark tracking | 🧪 |
| Blink ×N, wink, smile, mouth open | ML Kit classification + landmarks | ✅ / 🧪 mouth |
| Nod ×N, shake ×N, tilt | ML Kit Euler angles | 🧪 |
| Subject enters / leaves frame | ML Kit presence | ✅ |
| Clap ×N, snap ×N | transient detector (energy + spectral shape + decay) | 🧪 |
| Whistle | narrowband tone tracker | 🧪 |
| Loud sound | peak level | ✅ |
| Voice (system recognizer) | `SpeechRecognizer`, on-device preferred | ✅ |
| Voice (trained keyword spotter) | MFCC + DTW on the recording's PCM | 🧪 enroll 3–5 samples/phrase |
| Volume keys, Bluetooth buttons, shake, proximity wave, timer | platform | ✅ |

## Effects
| Feature | Status |
| --- | --- |
| GL compositor between camera and encoder (preview + recording) | ✅ |
| Person segmentation backgrounds: blur, color, animated GLSL, photo, video, 3D parallax | ✅ |
| Face-anchored stickers (2D / 3D head pose), body-anchored costume pieces, behind-person layers | ✅ 🧪 |
| 72 built-in stickers, 46 looks, import PNG stickers | ✅ |
| Beauty: smoothing, eyes, slim, nose, chin, teeth, lipstick, blush, glow, sharpen | ✅ 🧪 |
| Face modes (alien, zombie, robot, clown, ghost, vampire, gold, stone…) | 🧪 |
| Age looks (older / much older / younger / baby) — stylized, not a learned model | 🧪 |
| Fun distortions & stylizations, color looks, vignette, grain | ✅ |
| Effects with HDR / 8K / high-speed | ⛔ by design (SDR, capped at the render resolution, not in high-speed) |

## Monitoring overlays
Grids, aspect guides, safe areas, level, histogram, waveform, zebra, focus peaking, false color,
audio meter, timecode, gesture HUD, exposure info, thermal & storage chips — all ✅.
