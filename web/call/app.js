/* UltraX 26 browser call client.
 *
 * Talks the PeerJS protocol so it interoperates with the Android app (which implements the same
 * signaling over its own WebRTC stack). Media is peer-to-peer WebRTC; the signaling server only
 * relays the handshake. Group calls are a mesh: the host tells each newcomer who else is in the
 * room (over a JSON data channel) and the newcomer calls them directly.
 *
 * URL parameters:  to   host peer id to call (absent → this browser hosts a new call)
 *                  k    room key (guests with the key are auto-answered)
 *                  n    your display name
 *                  h p path key s   signaling overrides (host, port, path, api key, secure 1/0)
 */
(() => {
  'use strict';
  const cfg = window.ULTRAX_CONFIG || {};
  const qs = new URLSearchParams(location.search);
  const defaults = Object.assign({ host: '0.peerjs.com', port: 443, path: '/', key: 'peerjs', secure: true }, cfg.signaling || {});
  const sig = {
    host: qs.get('h') || defaults.host,
    port: parseInt(qs.get('p') || defaults.port, 10),
    path: qs.get('path') || defaults.path,
    key: qs.get('key') || defaults.key,
    secure: qs.has('s') ? (qs.get('s') === '1' || qs.get('s') === 'true') : defaults.secure,
  };
  const ALPHABET = 'abcdefghjkmnpqrstuvwxyz23456789';
  const randomToken = (n) => { const a = new Uint32Array(n); crypto.getRandomValues(a); let s = ''; for (const v of a) s += ALPHABET[v % ALPHABET.length]; return s; };
  const $ = (id) => document.getElementById(id);

  const isHost = !qs.get('to');
  const state = {
    peer: null, myId: null, name: '',
    target: qs.get('to'),
    roomKey: qs.get('k') || (isHost ? randomToken(8) : null),
    localStream: null, facing: 'user', muted: false, camOff: false,
    media: new Map(),   // peerId → { call, stream, tile, video }
    data: new Map(),    // peerId → DataConnection
    names: new Map(),
    inRoom: false, startedAt: 0, unread: 0, ended: false, pendingRing: null,
  };

  // ------------------------------------------------------------------------------------------
  // Pre-join
  // ------------------------------------------------------------------------------------------
  const nameInput = $('nameInput');
  nameInput.value = qs.get('n') || localStorage.getItem('ux.name') || '';
  $('prejoinTitle').textContent = isHost ? 'Start a video call' : 'You are invited to a video call';
  $('joinBtn').textContent = isHost ? 'Start call' : 'Join call';
  if (isHost) $('prejoinHint').textContent = 'You will get a link to share. Anyone who opens it joins in their browser — no app needed.';
  $('appLink').href = (cfg.appLinkBase || 'ultrax://call/') + location.search;

  async function getMedia(videoOnly) {
    const video = { width: { ideal: 1280 }, height: { ideal: 720 }, frameRate: { ideal: 30 }, facingMode: state.facing };
    const audio = { echoCancellation: true, noiseSuppression: true, autoGainControl: true };
    try {
      return await navigator.mediaDevices.getUserMedia(videoOnly ? { video } : { video, audio });
    } catch (e) {
      if (videoOnly) throw e;
      // No camera? Try audio only so the call still works.
      try { const s = await navigator.mediaDevices.getUserMedia({ audio }); showMediaError('No camera available — joining with audio only.'); return s; }
      catch (e2) { showMediaError('Microphone/camera access is required: ' + (e2.message || e2.name)); throw e2; }
    }
  }
  function showMediaError(msg) { const el = $('mediaError'); el.textContent = msg; el.classList.remove('hidden'); }

  async function startPreview() {
    try {
      state.localStream = await getMedia(false);
      const v = $('previewVideo'); v.srcObject = state.localStream; v.classList.toggle('mirror', state.facing === 'user');
      applyToggles();
    } catch (_) { /* message shown */ }
  }
  startPreview();

  function applyToggles() {
    const s = state.localStream; if (!s) return;
    s.getAudioTracks().forEach(t => t.enabled = !state.muted);
    s.getVideoTracks().forEach(t => t.enabled = !state.camOff);
    for (const id of ['preMic', 'micBtn']) { const b = $(id); b.setAttribute('aria-pressed', String(state.muted)); b.classList.toggle('off', state.muted); }
    for (const id of ['preCam', 'camBtn']) { const b = $(id); b.setAttribute('aria-pressed', String(state.camOff)); b.classList.toggle('off', state.camOff); }
    $('micBtn').querySelector('span').textContent = state.muted ? 'Unmute' : 'Mute';
    $('camBtn').querySelector('span').textContent = state.camOff ? 'Camera on' : 'Camera';
    $('previewOff').classList.toggle('hidden', !state.camOff);
    const self = state.media.get('__self'); if (self) self.tile.classList.toggle('cam-off', state.camOff);
    updateSelfLabel();
  }
  $('preMic').onclick = $('micBtn').onclick = () => { state.muted = !state.muted; applyToggles(); };
  $('preCam').onclick = $('camBtn').onclick = () => { state.camOff = !state.camOff; applyToggles(); };
  $('preFlip').onclick = $('flipBtn').onclick = flipCamera;

  async function flipCamera() {
    if (!state.localStream) return;
    state.facing = state.facing === 'user' ? 'environment' : 'user';
    try {
      const s = await getMedia(true);
      const newV = s.getVideoTracks()[0];
      const oldV = state.localStream.getVideoTracks()[0];
      for (const e of state.media.values()) {
        const pc = e.call && e.call.peerConnection; if (!pc) continue;
        for (const sender of pc.getSenders()) if (sender.track && sender.track.kind === 'video') sender.replaceTrack(newV);
      }
      if (oldV) { state.localStream.removeTrack(oldV); oldV.stop(); }
      state.localStream.addTrack(newV);
      newV.enabled = !state.camOff;
      const mirror = state.facing === 'user';
      $('previewVideo').classList.toggle('mirror', mirror);
      const self = state.media.get('__self'); if (self) { self.video.srcObject = state.localStream; self.video.classList.toggle('mirror', mirror); }
    } catch (e) { state.facing = state.facing === 'user' ? 'environment' : 'user'; console.warn('flip failed', e); }
  }

  $('joinForm').onsubmit = (ev) => {
    ev.preventDefault();
    if (!state.localStream) { showMediaError('Allow camera and microphone access first.'); return; }
    state.name = (nameInput.value || '').trim() || 'Guest';
    localStorage.setItem('ux.name', state.name);
    $('joinBtn').disabled = true; $('joinBtn').textContent = 'Connecting…';
    connect();
  };

  // ------------------------------------------------------------------------------------------
  // Signaling
  // ------------------------------------------------------------------------------------------
  function connect() {
    const id = isHost ? 'ux-' + randomToken(10) : undefined;
    const opts = { host: sig.host, port: sig.port, path: sig.path, key: sig.key, secure: sig.secure, debug: 1, config: { iceServers: cfg.iceServers || [], sdpSemantics: 'unified-plan' } };
    const peer = id ? new Peer(id, opts) : new Peer(opts);
    state.peer = peer;
    peer.on('open', (pid) => {
      state.myId = pid;
      $('myAddress').textContent = pid;
      if (isHost) { enterRoom(); setStatus('Waiting for people to join — share your link'); showInvite(true); $('inviteBtn').classList.remove('hidden'); }
      else { setStatus('Calling…'); dial(state.target, { name: state.name, key: state.roomKey, app: 'web' }); showCall(); }
    });
    peer.on('call', onIncomingCall);
    peer.on('connection', onIncomingData);
    peer.on('disconnected', () => { if (!state.ended) { setStatus('Signaling lost — reconnecting…'); setTimeout(() => { try { peer.reconnect(); } catch (_) {} }, 1000); } });
    peer.on('error', (e) => {
      console.warn('peer error', e);
      switch (e.type) {
        case 'peer-unavailable': if (!state.inRoom) endCall('Nobody answered', 'The person you are calling is not online. Ask them to open UltraX 26 (or the call link) and try again.'); break;
        case 'unavailable-id': state.peer.destroy(); connect(); break;
        case 'network': case 'server-error': case 'socket-error': case 'socket-closed': setStatus('Connection problem: ' + e.type); if (!state.inRoom) endCall('Could not connect', 'The signaling server (' + sig.host + ') is unreachable. Check your connection and try again.'); break;
        case 'browser-incompatible': endCall('Unsupported browser', 'This browser does not support WebRTC video calls.'); break;
        default: setStatus(e.message || e.type);
      }
    });
  }

  function enterRoom() { if (!state.inRoom) { state.inRoom = true; if (!state.startedAt) state.startedAt = Date.now(); } showCall(); }

  // ------------------------------------------------------------------------------------------
  // Calls (media)
  // ------------------------------------------------------------------------------------------
  function dial(peerId, meta) {
    if (peerId === state.myId || state.media.has(peerId)) return;
    const call = state.peer.call(peerId, state.localStream, { metadata: meta });
    if (!call) { setStatus('Could not start the call'); return; }
    bindCall(call);
  }

  function bindCall(call) {
    const pid = call.peer;
    const entry = ensureEntry(pid);
    entry.call = call;
    if (call.metadata && call.metadata.name) setName(pid, call.metadata.name);
    call.on('stream', (stream) => {
      entry.stream = stream; entry.video.srcObject = stream; entry.tile.classList.remove('connecting');
      enterRoom(); setStatus('Connected'); relayout();
    });
    call.on('close', () => removePeer(pid));
    call.on('error', (e) => { console.warn('call error', pid, e); removePeer(pid); });
    watchPc(call, pid, 0);
  }

  function watchPc(call, pid, tries) {
    const pc = call.peerConnection;
    if (!pc) { if (tries < 50) setTimeout(() => watchPc(call, pid, tries + 1), 100); return; }
    pc.addEventListener('connectionstatechange', () => {
      if (pc.connectionState === 'failed') removePeer(pid);
      else if (pc.connectionState === 'disconnected') setStatus('Reconnecting to ' + nameOf(pid) + '…');
      else if (pc.connectionState === 'connected') setStatus('Connected');
    });
  }

  function onIncomingCall(call) {
    const m = call.metadata || {};
    const pid = call.peer;
    const max = cfg.maxParticipants || 6;
    if (state.media.size >= max) { console.warn('room full'); sendLeave(pid); return; }
    const keyOk = !!state.roomKey && m.key === state.roomKey;
    if (state.inRoom || keyOk) {
      answer(call);
      if (isHost && !m.mesh) hostOnGuestJoined(pid, m.name);
    } else {
      ring(call);
    }
  }

  function answer(call) { call.answer(state.localStream); bindCall(call); }

  function ring(call) {
    if (state.pendingRing) { sendLeave(call.peer); return; }
    state.pendingRing = call;
    const m = call.metadata || {};
    $('ringName').textContent = m.name || call.peer;
    $('ring').classList.remove('hidden');
    showCall();
  }
  $('ringAccept').onclick = () => { const c = state.pendingRing; state.pendingRing = null; $('ring').classList.add('hidden'); if (c) { answer(c); if (isHost) hostOnGuestJoined(c.peer, (c.metadata || {}).name); } };
  $('ringDecline').onclick = () => { const c = state.pendingRing; state.pendingRing = null; $('ring').classList.add('hidden'); if (c) { sendLeave(c.peer); try { c.close(); } catch (_) {} } };

  /** Raw PeerJS LEAVE so the other side's ringing/outgoing call ends immediately. */
  function sendLeave(pid) { try { state.peer.socket.send({ type: 'LEAVE', dst: pid }); } catch (_) {} }

  // ------------------------------------------------------------------------------------------
  // Host duties: roster + chat relay over data channels
  // ------------------------------------------------------------------------------------------
  function hostOnGuestJoined(pid, name) {
    if (name) setName(pid, name);
    const others = [...state.media.keys()].filter(id => id !== pid && id !== '__self');
    const conn = state.peer.connect(pid, { serialization: 'json', reliable: true, metadata: { name: state.name, roster: others } });
    bindData(conn);
    conn.on('open', () => { conn.send({ type: 'name', name: state.name }); conn.send({ type: 'roster', peers: others }); });
  }

  function onIncomingData(conn) {
    bindData(conn);
    const m = conn.metadata || {};
    if (m.name) setName(conn.peer, m.name);
    if (Array.isArray(m.roster)) meshWith(m.roster);
  }

  function bindData(conn) {
    state.data.set(conn.peer, conn);
    conn.on('data', (d) => handleData(conn.peer, d));
    conn.on('close', () => { if (state.data.get(conn.peer) === conn) state.data.delete(conn.peer); });
    conn.on('error', (e) => console.warn('data error', conn.peer, e));
  }

  function handleData(pid, d) {
    if (!d || typeof d !== 'object') return;
    switch (d.type) {
      case 'name': setName(pid, d.name); break;
      case 'roster': meshWith(Array.isArray(d.peers) ? d.peers : []); break;
      case 'chat': addChat(d.name || nameOf(pid), String(d.text || ''), false); if (isHost) relayChat(pid, d); break;
      case 'bye': removePeer(pid); break;
    }
  }

  function meshWith(list) { for (const id of list) if (id && id !== state.myId && !state.media.has(id)) dial(id, { name: state.name, key: state.roomKey, mesh: true }); }

  function relayChat(fromPid, d) { const msg = { type: 'chat', name: d.name || nameOf(fromPid), text: d.text }; for (const [pid, c] of state.data) if (pid !== fromPid && c.open) c.send(msg); }

  // ------------------------------------------------------------------------------------------
  // Participants / tiles
  // ------------------------------------------------------------------------------------------
  function ensureEntry(pid) {
    let e = state.media.get(pid);
    if (e) return e;
    const tile = document.createElement('div'); tile.className = 'tile connecting'; tile.dataset.peer = pid;
    const video = document.createElement('video'); video.autoplay = true; video.playsInline = true; video.setAttribute('playsinline', '');
    const label = document.createElement('div'); label.className = 'label'; label.textContent = nameOf(pid);
    const avatar = document.createElement('div'); avatar.className = 'avatar'; avatar.textContent = (nameOf(pid) || '?').slice(0, 1).toUpperCase();
    const sub = document.createElement('div'); sub.className = 'sub'; sub.textContent = 'Connecting…';
    tile.append(video, avatar, sub, label);
    $('grid').appendChild(tile);
    e = { call: null, stream: null, tile, video, label, avatar };
    state.media.set(pid, e);
    relayout();
    return e;
  }

  function addSelfTile() {
    const tile = document.createElement('div'); tile.className = 'tile self'; tile.dataset.peer = '__self';
    const video = document.createElement('video'); video.autoplay = true; video.muted = true; video.playsInline = true; video.setAttribute('playsinline', ''); video.srcObject = state.localStream; video.classList.toggle('mirror', state.facing === 'user');
    const label = document.createElement('div'); label.className = 'label';
    const avatar = document.createElement('div'); avatar.className = 'avatar'; avatar.textContent = (state.name || 'Y').slice(0, 1).toUpperCase();
    const sub = document.createElement('div'); sub.className = 'sub'; sub.textContent = 'Camera off';
    tile.append(video, avatar, sub, label);
    $('grid').prepend(tile);
    state.media.set('__self', { call: null, stream: state.localStream, tile, video, label, avatar });
    updateSelfLabel(); applyToggles(); relayout();
  }
  function updateSelfLabel() { const e = state.media.get('__self'); if (e) e.label.textContent = (state.name || 'You') + ' (you)' + (state.muted ? ' · muted' : ''); }

  function setName(pid, name) {
    if (!name) return;
    state.names.set(pid, name);
    const e = state.media.get(pid); if (e) { e.label.textContent = name; e.avatar.textContent = name.slice(0, 1).toUpperCase(); }
    if (state.pendingRing && state.pendingRing.peer === pid) $('ringName').textContent = name;
  }
  const nameOf = (pid) => state.names.get(pid) || pid;

  function removePeer(pid) {
    const e = state.media.get(pid);
    if (e) { try { e.call && e.call.close(); } catch (_) {} e.tile.remove(); state.media.delete(pid); }
    const d = state.data.get(pid); if (d) { try { d.close(); } catch (_) {} state.data.delete(pid); }
    relayout();
    const others = [...state.media.keys()].filter(id => id !== '__self').length;
    if (state.inRoom && others === 0) {
      if (isHost) setStatus('Everyone left — share your link to invite more people');
      else endCall('The call ended', nameOf(pid) + ' left the call.');
    }
  }

  function relayout() {
    const n = state.media.size;
    const grid = $('grid');
    const landscape = window.innerWidth > window.innerHeight;
    const cols = n <= 1 ? 1 : n === 2 ? (landscape ? 2 : 1) : n <= 4 ? 2 : (landscape ? 3 : 2);
    grid.style.setProperty('--cols', cols);
    grid.dataset.count = n;
    $('count').textContent = n;
  }
  window.addEventListener('resize', relayout);

  // ------------------------------------------------------------------------------------------
  // Screens, status, timer, chat, invite
  // ------------------------------------------------------------------------------------------
  function showCall() {
    if (!$('call').classList.contains('hidden')) return;
    $('prejoin').classList.add('hidden'); $('call').classList.remove('hidden');
    $('previewVideo').srcObject = null;
    addSelfTile();
  }
  function setStatus(t) { $('status').textContent = t; }
  setInterval(() => { if (!state.startedAt) return; const s = Math.floor((Date.now() - state.startedAt) / 1000); $('timer').textContent = s >= 3600 ? `${Math.floor(s / 3600)}:${String(Math.floor(s % 3600 / 60)).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}` : `${String(Math.floor(s / 60)).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`; }, 1000);

  function addChat(from, text, mine) {
    const el = document.createElement('div'); el.className = 'msg' + (mine ? ' mine' : '');
    const who = document.createElement('b'); who.textContent = from; const body = document.createElement('span'); body.textContent = text;
    el.append(who, body); $('messages').appendChild(el); $('messages').scrollTop = 1e9;
    if (!mine && $('chat').classList.contains('hidden')) { state.unread++; const b = $('unread'); b.textContent = state.unread > 9 ? '9+' : state.unread; b.classList.remove('hidden'); }
  }
  $('chatForm').onsubmit = (ev) => {
    ev.preventDefault();
    const text = $('chatInput').value.trim(); if (!text) return;
    const msg = { type: 'chat', name: state.name, text };
    for (const c of state.data.values()) if (c.open) c.send(msg);
    addChat('You', text, true); $('chatInput').value = '';
  };
  $('chatBtn').onclick = () => { togglePanel('chat'); state.unread = 0; $('unread').classList.add('hidden'); setTimeout(() => $('chatInput').focus(), 50); };
  $('inviteBtn').onclick = () => togglePanel('invite');
  document.querySelectorAll('[data-close]').forEach(b => b.onclick = () => $(b.dataset.close).classList.add('hidden'));
  function togglePanel(id) { const el = $(id); const open = el.classList.contains('hidden'); $('chat').classList.add('hidden'); $('invite').classList.add('hidden'); if (open) el.classList.remove('hidden'); }

  function inviteUrl() {
    const u = new URL(location.href); u.search = '';
    u.searchParams.set('to', state.myId); u.searchParams.set('k', state.roomKey);
    if (sig.host !== defaults.host) u.searchParams.set('h', sig.host);
    if (sig.port !== parseInt(defaults.port, 10)) u.searchParams.set('p', sig.port);
    if (sig.path !== defaults.path) u.searchParams.set('path', sig.path);
    if (sig.key !== defaults.key) u.searchParams.set('key', sig.key);
    if (sig.secure !== defaults.secure) u.searchParams.set('s', sig.secure ? '1' : '0');
    return u.toString();
  }
  function showInvite(open) { const url = inviteUrl(); $('inviteUrl').textContent = url; if (navigator.share) $('shareBtn').classList.remove('hidden'); if (open) togglePanel('invite'); }
  $('copyBtn').onclick = async () => { try { await navigator.clipboard.writeText(inviteUrl()); $('copyBtn').textContent = 'Copied!'; setTimeout(() => $('copyBtn').textContent = 'Copy link', 1500); } catch (_) { prompt('Copy this link', inviteUrl()); } };
  $('shareBtn').onclick = () => navigator.share({ title: 'Video call', text: (state.name || 'Someone') + ' invites you to a video call — open in any browser, no app needed:', url: inviteUrl() }).catch(() => {});

  // ------------------------------------------------------------------------------------------
  // Leaving
  // ------------------------------------------------------------------------------------------
  function sayBye() { const bye = { type: 'bye' }; for (const c of state.data.values()) { try { if (c.open) c.send(bye); } catch (_) {} } for (const pid of state.media.keys()) if (pid !== '__self') sendLeave(pid); }
  function endCall(title, text) {
    if (state.ended) return; state.ended = true;
    sayBye();
    for (const e of state.media.values()) { try { e.call && e.call.close(); } catch (_) {} }
    for (const c of state.data.values()) { try { c.close(); } catch (_) {} }
    try { state.peer && state.peer.destroy(); } catch (_) {}
    if (state.localStream) state.localStream.getTracks().forEach(t => t.stop());
    $('call').classList.add('hidden'); $('prejoin').classList.add('hidden'); $('ended').classList.remove('hidden');
    $('endedTitle').textContent = title; $('endedText').textContent = text || '';
  }
  $('leaveBtn').onclick = () => endCall('You left the call', '');
  $('rejoinBtn').onclick = () => location.reload();
  window.addEventListener('beforeunload', () => { if (!state.ended) sayBye(); });
  window.addEventListener('pagehide', () => { if (!state.ended) sayBye(); });
})();
