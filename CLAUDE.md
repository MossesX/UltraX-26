# UltraX 26

A pro video recorder for the Samsung Galaxy S26 Ultra (and other recent Android phones), built
directly on Camera2 + MediaCodec with hands-free control: start/stop/pause takes with hand
gestures, face gestures, claps, whistles or voice. It also has AR effects (filters, stickers,
backgrounds) and Meet-style video calls where guests join from a browser link.

## Status (as of Oct 17 2026)

- **Built (Sep 30 – Oct 1 2026):** recording up to 8K/HDR with full manual camera control and an
  every-key editor; gesture/face/audio/voice/device triggers with a rule engine and Commands tab;
  record-your-own voice triggers; trigger-sound removal; pinch zoom; 46 looks / 72 stickers /
  backgrounds; WebRTC calls (PeerJS signaling) + browser client + Google Meet hand-off;
  diagnostics and a GL effects self-test.
- **Not verified on hardware:** README says it compiles in CI but has not yet been exercised on
  a physical S26 Ultra; trigger thresholds will need tuning.
- **Last work (Oct 1):** pinch spread detection; effects pipeline fixes (a shader used the GLSL
  reserved word `smooth`) and a build stamp / FX chip to show pipeline state.
- **Next:** not recorded in the repo. Ask Brett (likely: on-device testing feedback).

## Stack & layout

Single-module Kotlin app, Jetpack Compose, AGP 8.10.1, Kotlin 2.1.21, Gradle 8.14.3 wrapper,
compileSdk/targetSdk 36, minSdk 34, JDK 17. ML: MediaPipe Tasks + ML Kit face. Package
`com.ultrax26.recorder`.

```
app/src/main/kotlin/com/ultrax26/recorder/
  UltraXApp.kt, AppGraph.kt   app + manual DI graph
  camera/      Camera2 engine, capabilities, request applier, hidden Samsung camera IDs
  recording/   MediaCodec encoders, pre-roll, clip writer, audio, foreground service
  triggers/    rule engine, command catalog; audio/ (DSP, keyword spotter), vision/, device/
  effects/     AR effects renderer (GL shaders in effects/gl), MediaPipe ML
  calls/       WebRTC core, PeerJS signaling, invite links, Meet launcher
  ui/          Compose screens: camera, call, settings (triggers, all keys, diagnostics)
app/src/test/  JVM unit tests (DSP, detectors, keyword spotter, trigger engine, calls...)
docs/          ARCHITECTURE, FEATURES, GESTURES-AND-VOICE, EFFECTS, CALLS, INSTALL, S26-ULTRA-NOTES
web/call/      browser call client (published to GitHub Pages)
server/        optional self-hosted calling stack: peerjs-server + coturn + Caddy (compose)
tools/         check_shaders.py (GLSL validation), gen_assets.py (sticker art)
```

## Build, run, test

From README / `.github/workflows/android-build.yml`:

```
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # unit tests
./gradlew assembleRelease      # minified; allowed to fail in CI
python3 tools/check_shaders.py <path-to-glslangValidator>   # CI shader check
```

- Needs Android SDK 36 and network: Gradle downloads ~35 MB of MediaPipe models into
  `app/build/generated/ultrax-assets/` (SHA-256 verified, not committed).
- CI runs on every push/PR to any branch: shader check, unit tests, debug + release APKs
  (artifact `UltraX26-apks`). Pushes to `main` republish the `latest` release; phone install
  link: https://github.com/MossesX/UltraX-26/releases/latest/download/UltraX26-debug.apk
- `pages.yml` publishes `web/` to GitHub Pages on changes under `web/`.
- Self-hosted calls backend: `cd server && cp .env.example .env && docker compose up -d`
  (see `server/README.md`; it uses its own Caddy — on Brett's VPS, Coolify's Traefik owns
  80/443, so that compose would need adapting first).

## Conventions & decisions

- No third-party camera/media frameworks: Camera2 + MediaCodec directly.
- Release signing is optional via env (`ULTRAX_KEYSTORE_PATH`, `ULTRAX_KEYSTORE_PASSWORD`,
  `ULTRAX_KEY_ALIAS`, `ULTRAX_KEY_PASSWORD`); otherwise the debug key. A minified-build
  problem must never block the debug APK.
- Build stamp (`GIT_SHA`, `BUILD_TIME`, `BUILD_NUMBER`) is shown in the app so the phone shows
  which commit it runs — use it when debugging on device.
- A broken shader disables the whole effects pipeline, so CI validates GLSL; keep it passing.
- Recording and detection stay on the phone; internet is only for calls.
- Calls default to public PeerJS Cloud / Google STUN / Open Relay TURN.

## Related repos

- Not built on the AmberX framework (MossesX/AmberX-AXF); plain Android/Compose, standalone.

## Working with Brett (same in every MossesX repo)

- **Where he works:** mostly from his Android phone (Samsung Galaxy S26 Ultra), often through **Moshi** (mosh) into his Hostinger VPS, where Claude Code runs. Keep replies phone-readable: lead with the result, short paragraphs, no walls of text.
- **Model choice:** before any build task, recommend which model to use and why, then let him pick from the top three (Fable, Opus, Sonnet). Use Fable where it's warranted, Opus for most work, and Sonnet only when you're sure Opus isn't needed.
- **The repo is the shared memory.** Sessions on the phone (Moshi), desktop, web and claude.ai don't share chat history, so anything a future session needs must live in this repo. Commit and push often with clear messages, and update the **Status** section of this file whenever something meaningful changes.
- **No secrets in the repo:** no tokens, passwords, keys or IPs with credentials. Use environment variables or GitHub Actions secrets.
- **GitHub:** account `MossesX`. A new repo must exist on GitHub before a session can push to it.
- **Server:** a Hostinger VPS running **Coolify**. Coolify's Traefik proxy owns ports 80/443, so new web apps go behind it as Coolify apps (no separate nginx). His domain is `amberxai.com`.
- **axHub:** his connector hub on the VPS (repo `MossesX/axHub`). If the axHub MCP server is configured in this Claude Code install, use its `list_services`, `describe_service` and `call` tools to reach his other services instead of asking him for credentials.
