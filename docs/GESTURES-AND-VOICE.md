# Gestures, sounds and voice — how it works and how to tune it

Everything hands-free runs through **rules**: *when \<trigger\> then \<action\>*. Edit them in
**Settings → Triggers**. Rules only fire while the recorder is **armed** (the green ARMED chip; a
voice "arm"/"disarm" rule is included), unless a rule is marked *works while disarmed*
(volume keys and Bluetooth buttons are, by default).

## Pipeline
```
camera analysis stream (≈640×360 YUV, 12 fps)
   → MediaPipe GestureRecognizer → HandGestureInterpreter → HandHeld / HandReleased / Wave / …
   → ML Kit FaceDetector       → FaceGestureInterpreter → BlinkBurst / Wink / HeadNod / …
microphone PCM (the same stream the encoder uses)
   → TransientDetector (clap, snap) → BurstCounter → ClapBurst(count)
   → WhistleDetector, LoudnessDetector
   → KeywordSpotter (MFCC + DTW against your enrolled phrases) → Voice(text, KEYWORD)
system SpeechRecognizer (separate process) → Voice(text, SYSTEM)
volume keys / MediaSession buttons / accelerometer / proximity → device events
                              ↓
                       TriggerEngine (rules, hold, cooldown, states, countdown)
                              ↓
        RecordingController.perform(START / STOP / PAUSE / … )  +  Feedback (haptic, beep, flash, TTS)
```

## Hand gestures
- The model labels: **Thumb_Up, Thumb_Down, Open_Palm, Closed_Fist, Victory, Pointing_Up, ILoveYou**.
- A gesture must be **held** (default 600–800 ms) so a passing hand doesn't trigger. The HUD shows
  the held gesture and timer. After it fires, you must **release** (drop the hand or change gesture)
  before the same rule can fire again.
- Derived gestures (OK sign, pinch, rock-on, call-me, N fingers) come from the 21 landmarks and are
  less robust in poor light; increase *hold* if they flicker.
- **Wave**: open palm moving left–right ≥ 3 swings. **Hands up**: both wrists in the upper 45 % of
  the frame. **Visual clap**: two palms rushing together (a silent alternative to the audio clap).
- **Sequences**: e.g. *Open palm → Closed fist* within 2.5 s per step — good for a "confirm" pattern.
- Tips: keep hands inside the frame and within ~3 m for the default 640×360 analysis size; bump
  *Settings → Triggers → Analysis size* to 1280×720 for longer distances (costs CPU/battery).

## Face
- **Blink ×N**: both eyes closed 40–500 ms then open again; blinks less than 700 ms apart form a
  burst; the burst count must **equal** N. Blink deliberately and quickly. Glasses with strong
  reflections reduce eye-probability accuracy — raise *eye open threshold* if it double counts.
- **Wink**: exactly one eye closed for 150–1200 ms.
- **Smile** / **Mouth open** are hold-style (default 1 s / 0.7 s).
- **Nod / shake** count oscillations of pitch / yaw beyond ±8° / ±10° from a slow baseline; a burst
  ends after 1 s of stillness.
- **Subject enters / leaves**: great for solo creators — start when you step in front of the lens,
  stop N seconds after you walk away.

## Sounds
- **Clap**: a broadband impulse ≥ 12–24 dB above the running background that decays ≥ 9 dB within
  160 ms. Count is exact (double clap ≠ triple clap). *Sensitivity* trades false positives for
  reach; in loud rooms lower it. Our own confirmation beeps are ignored automatically.
- **Snap**: like clap but shorter and brighter (centroid > 2.5 kHz).
- **Whistle**: a near-pure tone between 500 Hz and 5 kHz stable for ≥ 250–400 ms.
- **Loud sound**: peak above the dBFS threshold.

## Voice — two engines
1. **System speech recognizer** (Google/Samsung, on-device when available). Free-form: say the
   phrase anywhere in a sentence ("okay, stop recording please"). Android may hand the microphone
   to only one client at a time; if the system recognizer goes quiet while you record, use engine 2.
2. **Built-in keyword spotter** — trained by *you*, runs inside the app on the same audio the encoder
   receives, so it always hears you while recording and works fully offline:
   - Settings → Triggers → *Voice → Trained commands* → type a phrase (e.g. "action"), tap
     **Record sample** and say it; repeat **3–5 times** in the way you'd say it on set.
   - Enroll every phrase you use in rules (the rule's phrase must match the enrolled command name).
   - *Sensitivity* moves the DTW acceptance threshold; the HUD shows the last match score vs. the
     threshold so you can tune it. *Margin* rejects matches that are too close to another command.
   - Phrases with 2–3 syllables and different vowels work best ("action", "cut", "hold on").

## Feedback and countdown
- Any fired rule confirms with haptics, a short tone (recorded into the clip if the beep volume is
  up — disable *Beep* for silent sets) and a screen flash; TTS announcements are optional.
- START rules triggered by gestures/sounds go through a **countdown** (default 3 s) with tick tones so
  you can get into position; a stop gesture or voice "cancel" aborts it. Voice and device triggers
  skip the countdown by default.

## Defaults that ship
Thumbs up → start · thumbs down / fist → stop · open palm → pause/resume · victory → frame grab ·
blink ×3 → start/stop · double clap → start/stop · "start recording" / "stop recording" / "pause" /
"resume" / "snapshot" / "marker" / "arm" / "disarm" · volume keys & Bluetooth button → start/stop.
