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
