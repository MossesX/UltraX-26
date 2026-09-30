# Self-hosting the calling backend

UltraX 26 video calls work with **no setup**: the app and the browser client default to the free
PeerJS Cloud signaling server (`0.peerjs.com`), Google's STUN servers and the Open Relay Project's
TURN relays. Those are best-effort public services. For reliability, privacy or a company deployment
you can run everything yourself with the files in this folder.

What runs where:

| Piece | Purpose | Sees your media? |
| --- | --- | --- |
| **peerjs-server** | Signaling — relays the WebRTC handshake (offers, answers, ICE candidates) between peers by id | No |
| **coturn** (TURN) | Relays encrypted media packets when a direct peer-to-peer path is blocked (symmetric NAT, corporate networks, some mobile carriers) | Only encrypted packets (DTLS-SRTP); it cannot decrypt them |
| **Caddy** | HTTPS for the browser client (`/call/`) and the signaling websocket (`/peerjs`) | No |

## 1. Deploy

```bash
git clone https://github.com/MossesX/UltraX-26 && cd UltraX-26/server
cp .env.example .env            # set DOMAIN, PUBLIC_IP, PEERJS_KEY, TURN_PASSWORD
docker compose up -d
```

Open ports on the firewall: **80, 443** (TCP, web + signaling), **3478** (TCP+UDP, TURN),
**49160–49200** (UDP, TURN relay range from `turnserver.conf`).

Check: `https://DOMAIN/call/` shows the browser client and `https://DOMAIN/peerjs/id` returns a random id.

## 2. Point the app at it

In the app: **Settings ▸ Calls ▸ Servers**

| Setting | Value |
| --- | --- |
| Web client URL | `https://DOMAIN/call/` |
| Signaling host | `DOMAIN` |
| Signaling port | `443` |
| Signaling path | `/` |
| Signaling key | your `PEERJS_KEY` |
| Secure | on |
| ICE servers | `stun:DOMAIN:3478` <br> `turn:DOMAIN:3478 ultrax <TURN_PASSWORD>` <br> `turn:DOMAIN:3478?transport=tcp ultrax <TURN_PASSWORD>` |

Tap **Apply ICE servers**, then **Reconnect now**. Invite links created afterwards carry the
signaling overrides (`h`, `p`, `path`, `key`, `s` parameters), so browser guests use your server even
if they open the link on the public GitHub Pages copy of the client. To make the **browser client**
default to your servers as well, edit the `ULTRAX_CONFIG` block at the top of `web/call/index.html`
(Caddy serves that folder).

## 3. Notes

- The PeerJS key is not a secret; it just namespaces ids on the server. Real access control is the
  per-call room key inside each link plus the fact that ids are unguessable.
- TURN credentials in this compose file are static (long-term credentials). Rotate `TURN_PASSWORD`
  whenever you share it; coturn also supports time-limited credentials (`use-auth-secret`) if you
  want to issue them from your own backend.
- Behind Cloudflare or another proxy, enable websockets and keep `--proxied true` on peerjs-server.
- Scaling: signaling is tiny; TURN bandwidth is the real cost (only calls that cannot go direct use
  it). A small VPS handles dozens of concurrent relayed calls.
