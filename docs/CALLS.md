# Video calls

UltraX 26 makes video calls the way Google Meet or Google Messages links do: **you share a link, the
other person opens it in any browser and is in the call.** They do not need the app, an account or a
phone number. Two phones running UltraX 26 can also call each other directly by address.

## Using it

**Start a call**

1. Tap **Call** on the camera screen → **Create call link**.
2. Send the link with **Share**, **Text** (SMS) or **Copy** — or let the other person scan the QR code.
3. When they open it, their browser asks for camera/microphone permission and joins. You are connected
   as soon as the connection is negotiated (usually 1–3 s).
4. Tap **Invite** during the call to bring in more people (up to the *Max participants* setting).

**Receive calls at a fixed address**

Set **Settings ▸ Calls ▸ My address** (e.g. `sam`). Whenever the app is open and *Reachable for
incoming calls* is on, anyone can call you:

- from another UltraX 26 phone: **Call ▸ Call someone directly ▸** `ux-sam`
- from a browser: `https://mossesx.github.io/UltraX-26/call/?to=ux-sam`

Incoming calls ring (system ringtone) and show an Answer / Decline dialog.

**Hands-free.** The trigger engine has three call actions: *Answer incoming video call*, *Hang up
video call* and *Mute / unmute call microphone*. By default the voice commands **“answer”** and
**“hang up”** are enabled; thumbs-up → answer and open-palm → hang up rules exist but are off. Edit
them under **Gestures**.

**During the call** the screen shows every participant (yourself included) in a Meet-style grid.
Controls: mute, camera on/off, speaker/earpiece, **Effects** (all filters, costumes and backgrounds
apply to your outgoing video), chat (goes to browser guests too), invite, hang up, and a **Record**
toggle — the call does not stop you from recording with the full camera pipeline.

**In the browser** guests get a pre-join screen (name, camera/mic check, camera flip), the same grid,
mute/camera/flip, chat and the invite link (when they are hosting). The page also works as a
stand-alone starting point: opening `/call/` with no parameters hosts a new call from the browser, and
UltraX phones can join it via the link or by dialing its address.

## How it works

```
 phone A ──┐                                         ┌── browser / phone B
           │  signaling (PeerJS protocol, websocket)  │
           ├────────────► peerjs-server ◄─────────────┤   offers / answers / ICE candidates only
           │                                         │
           └────────── WebRTC media (DTLS-SRTP) ──────┘   direct, or through a TURN relay
```

- **Signaling** speaks the open [PeerJS](https://peerjs.com) protocol. The app implements it with
  OkHttp websockets (`calls/PeerJsSignaling.kt`); the browser uses the PeerJS library (vendored,
  MIT). The default server is the free PeerJS Cloud (`0.peerjs.com`). Peer ids are `ux-<address>` or
  `ux-<random>`.
- **Media** is WebRTC. The app uses the `io.github.webrtc-sdk` build of libwebrtc (`calls/WebRtcCore.kt`,
  `calls/PeerLink.kt`); hardware H.264/VP8/VP9/AV1 codecs are negotiated automatically.
  The **video source is the effects renderer**: the GL compositor that sits between the camera and
  the recorder gets a third output surface, which a `SurfaceTextureHelper` turns into WebRTC frames.
  Filters and virtual backgrounds therefore appear in calls at zero extra cost. (Turn this off with
  *Apply effects to my call video* to send the plain camera image.)
- **Audio** uses `VOICE_COMMUNICATION` with the hardware echo canceller and noise suppressor;
  the phone switches to communication audio mode and speakerphone (configurable).
- **Connectivity**: Google STUN discovers the public address; when a direct path is impossible the
  Open Relay Project TURN servers relay the encrypted packets. Both are replaceable (see below).
- **Room keys**: each *Create call link* mints a random 8-character key carried in the link (`k=`).
  Offers whose metadata carries the right key are auto-answered; anything else rings. A new key is
  created per call, so an old link stops working when you hang up.
- **Group calls** are a full mesh. The host sends every newcomer the roster over a JSON data channel
  (`roster` message / connection metadata); the newcomer then calls each existing participant directly
  with the room key. Chat messages travel over the same data channels; the host relays them.
  Upload bandwidth grows with participants, hence the default cap of 6.
- **Foreground service**: while a call is active `RecordingService` runs in call-only mode so the
  camera and microphone keep working if you switch apps.
- **Links** look like
  `https://mossesx.github.io/UltraX-26/call/?to=ux-abc123&k=qrst2345` and optionally carry `n`
  (name) plus signaling overrides `h`, `p`, `path`, `key`, `s`. The app registers the same URL (and
  `ultrax://call?…`) so a link opened on a phone with UltraX 26 joins directly in the app.

## Hosting the browser page

The link points at the browser client in `web/call/`. The repository ships a GitHub Pages workflow
(`.github/workflows/pages.yml`) that publishes `web/` on every push to `main`:

1. Repository **Settings ▸ Pages ▸ Build and deployment ▸ Source: GitHub Actions** (one time).
2. Push (or run the workflow manually). The page appears at `https://<owner>.github.io/UltraX-26/call/`.
3. If your GitHub user is not `mossesx`, set **Settings ▸ Calls ▸ Web client URL** in the app to your
   Pages URL (and, for link opening, update the `android:host` in `AndroidManifest.xml`).

Any static host works; the page is three files plus the PeerJS library. To make the page default to
your own signaling/TURN servers, edit the `ULTRAX_CONFIG` block at the top of `web/call/index.html`.

## Running your own servers

`server/` contains a Docker Compose bundle (peerjs-server + coturn + Caddy with automatic HTTPS) and
a README with the exact settings to enter in the app. Links created after switching carry the server
parameters, so guests are routed to your server even from the public page.

## Privacy & security

- Media is end-to-end encrypted with DTLS-SRTP between the participants (each pair in a group call).
  The signaling server only sees the session descriptions and candidate addresses; a TURN relay only
  sees encrypted packets.
- Public defaults (PeerJS Cloud, Open Relay) are third-party services with their own terms and no
  availability guarantee. Self-host for anything sensitive.
- Call links are capability URLs: anyone holding one can join while the call is open. Share them
  like you would share a Meet link.

## Troubleshooting

| Symptom | Likely cause / fix |
| --- | --- |
| Status stays *Connecting to 0.peerjs.com…* | Network blocks the websocket; try mobile data, or self-host on port 443 |
| *Nobody is listening at that address* | The other phone is not online (app closed, or *Reachable* off) |
| Connected but black video / no audio | Both sides behind strict NATs and TURN unreachable — check ICE servers; on corporate Wi-Fi use `?transport=tcp` TURN |
| Browser shows *Microphone/camera access is required* | Permission denied, or the page is not served over HTTPS (browsers require a secure origin) |
| Guests can't see each other in a group call | A guest's network blocks direct paths; TURN needed. Everyone still sees the host |
| Effects not visible in the call | *Apply effects to my call video* is off, or high-speed recording is active (effects are disabled in slow-motion modes) |
| Link opens in the browser instead of the app | Android needs the app verified for the domain or chosen as the default handler once; the `ultrax://` link in the page footer always opens the app |
