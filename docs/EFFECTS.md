# Effects: filters, costumes, backgrounds, beauty and age

Everything here runs on the phone (OpenGL ES + MediaPipe). No asset from Google Meet, Snapchat or
any other product is copied; the built-in art is original vector artwork drawn for this app, and you
can import your own PNG stickers and photo/video backgrounds.

Open the **Effects** chip on the camera screen. Effects apply live to the preview *and* the recording.

## Pipeline
```
camera → SurfaceTexture → GL
  1. camera frame → FBO                         (scene = sensor orientation)
  2. background: blur pyramid | color | animated GLSL scene | photo | video | 3-layer parallax
     + "behind me" stickers (capes, wings, sun rays)
  3. composite person over background using the segmentation mask (feather, edge shift, light wrap)
  4. face pass: liquify warps (eyes, slim, nose, chin, big/tiny head…), skin smoothing (frequency
     separation), brightening, teeth, lipstick, blush, eye brighten, sharpen, glow, age looks, face modes
  5. style pass: fun modes (cartoon, thermal, VHS, glitch, halftone, sketch, pixel, kaleidoscope…),
     color looks, vignette, grain
  6. face-anchored stickers (2D roll-following or full 3D head pose)
  → preview surface (mirrored for the selfie camera) and encoder surface (with camera timestamps)
```
Tracking runs on the analysis stream (~12 fps): MediaPipe Face Landmarker (478 points, blendshapes,
head transform), Image Segmenter (multi-class: person / hair / face skin / clothes) and Pose
Landmarker (shoulders, chest for costume pieces). Landmarks are smoothed and predicted between
detections; expect a little lag on fast head moves.

## What you get
- **Looks** (one tap): Cop, Wizard, Alien, Astronaut, Pirate, Zombie, Vampire, Robot, Superhero,
  Clown, Royalty, Cat, Dog, Bunny, Angel, Devil, Doctor, Chef, Viking, Cowboy, Beach day, Office,
  Library, Mountains, City night, Blur, Older, Much older, Younger, Baby face, Glam, Natural touch-up,
  Vintage film, Noir, Cyberpunk, Blockbuster, Big head, Tiny head, Cartoon, Thermal, VHS, Glitch,
  Pencil sketch, Comic, Mirror, Kaleidoscope.
- **Stickers** (72 built-in, stackable, each with size / position / depth / opacity / rotation mode /
  behind-me / anchor controls): police cap, wizard hat, crown, top hat, party hat, cowboy hat, Santa
  hat, chef hat, Viking helmet, pirate hat, graduation cap, beanie, tiara, halo, devil horns, bunny /
  cat / dog ears, headphones, propeller cap, alien antennae, flower crown, ninja band, sparkles;
  aviators, nerd / heart / star / pixel glasses, monocle, eye patch, cyborg eye, VR headset, alien eyes,
  hero mask; handlebar & chevron mustaches, goatee, full / wizard / pirate beards; clown / pig / dog /
  cat noses, skull, robot faceplate, astronaut helmet, vampire fangs, bandit bandana; bow tie, necktie,
  police badge, stethoscope, space-suit collar, hero emblem, lei, scarf, gold chain, doctor's coat;
  cape, angel wings, bat wings, sun rays; hoop earring, speech / thought bubbles, butterfly, hearts,
  REC / LIVE badges, film frame.
- **Backgrounds**: blur, solid colors, 16 animated GLSL scenes (hyperspace, nebula, aurora, ocean
  sunset, cyber grid, digital rain, disco, clouds, underwater, lava lamp, snowfall, fireflies, gradient
  wave, bokeh, retro sun, checker room), 6 head-tracked **3D parallax** scenes (mountain lake, city at
  night, space station, library, beach, office), your own photos and looping videos.
- **Beauty**: skin smoothing, brighten, eye enlarge, face slim, nose slim, chin shorten, teeth
  whitening, eye brighten, lipstick (7 colors), blush, sharpen, glow.
- **Face modes**: Alien, Zombie, Robot, Clown, Ghost, Vampire, Gold statue, Stone, Blue creature,
  Green giant, Frozen (masked shader treatments, intensity slider).
- **Age**: Older, Much older (procedural wrinkles in face space, age spots, desaturation, jowl warp,
  gray hair via the hair mask), Younger (smoothing, saturation, eye enlarge), Baby face (big head, big
  eyes, small nose/mouth, rosy cheeks). These are **stylized approximations**, not a learned aging
  model such as the ones behind FaceApp/Snapchat.
- **Fun**: big / tiny head, long / wide face, fisheye, mirror, pixel face, pixelate, cartoon,
  thermal, negative, VHS, glitch, comic halftone, pencil sketch, night vision, rainbow hue,
  kaleidoscope, painterly, swirl.
- **Color looks**: warm, cool, teal & orange, black & white, sepia, vintage, faded, matte, vivid, noir,
  cross process, cyberpunk, pastel, golden hour, moonlight, plus vignette and grain.

## Costs and limits
- While any effect is active the recording is rendered through the GPU at the **effects render
  resolution** (Settings → Effects; 4K default). 8K is not real-time with effects, so the recording is
  capped at that resolution, and HDR is recorded as SDR (the compositor works in 8-bit).
- High-speed (120/240 fps) sessions cannot use effects; time-lapse decimation and interval capture
  use the direct camera→encoder path, so they record in real time while effects are on.
- Battery and heat: expect roughly the cost of a video call with effects. The thermal chip still
  applies (auto-downgrade / auto-stop).
- If stickers appear flipped or offset on a specific phone, use Settings → Effects → Troubleshooting
  (flip camera texture / flip mask / invert yaw or pitch) and enable the mask overlay.
- Body-anchored costume pieces use Pose Landmarker; when pose tracking is off, shoulders and chest are
  estimated from the face.

## When costumes or backgrounds do nothing

Stickers, costumes and face modes need the **face mesh** model; backgrounds and behind-person layers
need the **segmentation** model. Both run on the gesture analysis stream, and all of it runs behind the
GL pipeline. The Effects panel header now says which link is missing:

| Status line | Meaning / fix |
| --- | --- |
| *Pipeline off: effects start with the next clip* | You are recording on the direct camera→encoder path. Stop the clip, or turn on *Settings ▸ Effects ▸ Keep pipeline on* so effects can be switched mid-recording. Opening the panel while idle starts the pipeline immediately. |
| *Face: model not loaded* / *Person mask: model not loaded* with an *Error:* | The MediaPipe model failed to initialize. The error text names the cause (missing asset, GPU delegate). Switch *Gestures ▸ Tuning ▸ Hands ▸ delegate* to CPU and reopen the panel. |
| *Face: model ready, no face seen* | Detection works but no face is in the gesture camera's view. Check *Gestures ▸ Tuning ▸ Gesture camera* — if it is set to the other camera, the effects track the wrong lens. |
| *Person mask: no mask yet* | The segmenter runs but has returned nothing; try the single-class model (*Settings ▸ Effects ▸ Multi-class segmentation* off). |
| *No analysis frames* | Gesture analysis is disabled or the camera could not add the analysis stream (high-speed mode, or a session limit). Enable it under *Gestures ▸ Tuning*. |
| *GL: …* | The compositor hit a GPU error; lower *Settings ▸ Effects ▸ Render resolution*. |

**Diagnostics ▸ Effects self-test** loads every model with the GPU and CPU delegates, runs one frame
through each and appends the timings or the exact exception to the device report — share that report
when asking for help.
