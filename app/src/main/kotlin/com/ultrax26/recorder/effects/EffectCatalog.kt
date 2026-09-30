package com.ultrax26.recorder.effects

import java.util.UUID

/** A built-in sticker: a vector drawable plus default placement. */
data class StickerAsset(
    val id: String,
    val name: String,
    val category: StickerCategory,
    val drawable: String,
    val anchor: Anchor,
    val scale: Float,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val offsetZ: Float = 0f,
    val rotation: RotationMode = RotationMode.BILLBOARD,
    val behind: Boolean = false,
    val spin: Float = 0f,
    val pulse: Float = 0f,
    val aspect: Float = 1f,       // drawable width / height
) {
    fun layer(id: String = UUID.randomUUID().toString()) = StickerLayer(id, this.id, anchor, scale, offsetX, offsetY, offsetZ, rotation, behind, null, spin, pulse)
}

data class ProceduralBackground(val id: String, val name: String, val shaderMode: Int, val emoji: String)

data class ParallaxLayer(val drawable: String, val depth: Float, val scale: Float = 1.15f)
data class ParallaxScene(val id: String, val name: String, val emoji: String, val layers: List<ParallaxLayer>)

/** A preset bundle ("Cop", "Wizard", "Old age"…). Applies on top of the current stack unless `replace`. */
data class EffectLook(val id: String, val name: String, val emoji: String, val category: String, val apply: (EffectsSettings) -> EffectsSettings)

object EffectCatalog {
    private fun a(id: String, name: String, cat: StickerCategory, anchor: Anchor, scale: Float, offsetY: Float = 0f, offsetX: Float = 0f, rot: RotationMode = RotationMode.BILLBOARD, behind: Boolean = false, offsetZ: Float = 0f, spin: Float = 0f, pulse: Float = 0f, aspect: Float = 1f) =
        StickerAsset(id, name, cat, "fx_$id", anchor, scale, offsetX, offsetY, offsetZ, rot, behind, spin, pulse, aspect)

    val stickers: List<StickerAsset> = listOf(
        // ---- Headwear (anchored above the head; y is down, so negative offsets move up) ----
        a("cop_hat", "Police cap", StickerCategory.HEADWEAR, Anchor.HEAD_TOP, 1.45f, offsetY = -0.05f, rot = RotationMode.HEAD_3D),
        a("wizard_hat", "Wizard hat", StickerCategory.HEADWEAR, Anchor.HEAD_TOP, 1.7f, offsetY = -0.45f, rot = RotationMode.HEAD_3D),
        a("crown", "Crown", StickerCategory.HEADWEAR, Anchor.HEAD_TOP, 1.15f, offsetY = -0.1f, rot = RotationMode.HEAD_3D),
        a("top_hat", "Top hat", StickerCategory.HEADWEAR, Anchor.HEAD_TOP, 1.35f, offsetY = -0.45f, rot = RotationMode.HEAD_3D),
        a("party_hat", "Party hat", StickerCategory.HEADWEAR, Anchor.HEAD_TOP, 0.9f, offsetY = -0.5f, rot = RotationMode.HEAD_3D),
        a("cowboy_hat", "Cowboy hat", StickerCategory.HEADWEAR, Anchor.HEAD_TOP, 1.9f, offsetY = -0.15f, rot = RotationMode.HEAD_3D),
        a("santa_hat", "Santa hat", StickerCategory.HEADWEAR, Anchor.HEAD_TOP, 1.5f, offsetY = -0.3f, rot = RotationMode.HEAD_3D),
        a("chef_hat", "Chef hat", StickerCategory.HEADWEAR, Anchor.HEAD_TOP, 1.3f, offsetY = -0.45f, rot = RotationMode.HEAD_3D),
        a("viking_helmet", "Viking helmet", StickerCategory.HEADWEAR, Anchor.HEAD_TOP, 1.6f, offsetY = -0.05f, rot = RotationMode.HEAD_3D),
        a("pirate_hat", "Pirate hat", StickerCategory.HEADWEAR, Anchor.HEAD_TOP, 1.9f, offsetY = -0.25f, rot = RotationMode.HEAD_3D),
        a("grad_cap", "Graduation cap", StickerCategory.HEADWEAR, Anchor.HEAD_TOP, 1.5f, offsetY = -0.15f, rot = RotationMode.HEAD_3D),
        a("beanie", "Beanie", StickerCategory.HEADWEAR, Anchor.HEAD_TOP, 1.35f, offsetY = -0.1f, rot = RotationMode.HEAD_3D),
        a("tiara", "Tiara", StickerCategory.HEADWEAR, Anchor.HEAD_TOP, 1.0f, offsetY = 0.05f, rot = RotationMode.HEAD_3D),
        a("halo", "Halo", StickerCategory.HEADWEAR, Anchor.HEAD_TOP, 1.1f, offsetY = -0.45f, rot = RotationMode.NONE, pulse = 0.05f),
        a("devil_horns", "Devil horns", StickerCategory.HEADWEAR, Anchor.HEAD_TOP, 1.1f, offsetY = -0.05f, rot = RotationMode.HEAD_3D),
        a("bunny_ears", "Bunny ears", StickerCategory.HEADWEAR, Anchor.HEAD_TOP, 1.2f, offsetY = -0.55f, rot = RotationMode.HEAD_3D),
        a("cat_ears", "Cat ears", StickerCategory.HEADWEAR, Anchor.HEAD_TOP, 1.25f, offsetY = -0.15f, rot = RotationMode.HEAD_3D),
        a("dog_ears", "Dog ears", StickerCategory.HEADWEAR, Anchor.HEAD_TOP, 1.5f, offsetY = 0.15f, rot = RotationMode.HEAD_3D),
        a("headphones", "Headphones", StickerCategory.HEADWEAR, Anchor.FACE_CENTER, 1.55f, offsetY = -0.25f, rot = RotationMode.HEAD_3D),
        a("propeller_cap", "Propeller cap", StickerCategory.HEADWEAR, Anchor.HEAD_TOP, 1.3f, offsetY = -0.3f, rot = RotationMode.HEAD_3D),
        a("alien_antennae", "Alien antennae", StickerCategory.HEADWEAR, Anchor.HEAD_TOP, 1.1f, offsetY = -0.5f, rot = RotationMode.HEAD_3D),
        a("flower_crown", "Flower crown", StickerCategory.HEADWEAR, Anchor.FOREHEAD, 1.3f, offsetY = -0.2f, rot = RotationMode.HEAD_3D),
        a("ninja_band", "Ninja headband", StickerCategory.HEADWEAR, Anchor.FOREHEAD, 1.3f, offsetY = -0.05f, rot = RotationMode.HEAD_3D),
        a("sparkles", "Sparkles", StickerCategory.HEADWEAR, Anchor.HEAD_TOP, 1.6f, offsetY = -0.4f, rot = RotationMode.NONE, spin = 20f, pulse = 0.12f),
        // ---- Eyewear ----
        a("aviators", "Aviators", StickerCategory.EYEWEAR, Anchor.EYES, 1.2f, offsetY = 0.02f, rot = RotationMode.HEAD_3D, aspect = 2.4f),
        a("nerd_glasses", "Nerd glasses", StickerCategory.EYEWEAR, Anchor.EYES, 1.15f, rot = RotationMode.HEAD_3D, aspect = 2.4f),
        a("heart_glasses", "Heart glasses", StickerCategory.EYEWEAR, Anchor.EYES, 1.25f, rot = RotationMode.HEAD_3D, aspect = 2.4f),
        a("star_glasses", "Star glasses", StickerCategory.EYEWEAR, Anchor.EYES, 1.3f, rot = RotationMode.HEAD_3D, aspect = 2.4f),
        a("pixel_shades", "Pixel shades", StickerCategory.EYEWEAR, Anchor.EYES, 1.2f, rot = RotationMode.HEAD_3D, aspect = 2.4f),
        a("monocle", "Monocle", StickerCategory.EYEWEAR, Anchor.RIGHT_EYE, 0.42f, offsetY = 0.08f, rot = RotationMode.HEAD_3D),
        a("eye_patch", "Eye patch", StickerCategory.EYEWEAR, Anchor.LEFT_EYE, 0.55f, rot = RotationMode.HEAD_3D),
        a("cyborg_eye", "Cyborg eye", StickerCategory.EYEWEAR, Anchor.RIGHT_EYE, 0.45f, rot = RotationMode.HEAD_3D, pulse = 0.08f),
        a("vr_headset", "VR headset", StickerCategory.EYEWEAR, Anchor.EYES, 1.35f, rot = RotationMode.HEAD_3D, aspect = 2.4f),
        a("alien_eyes", "Alien eyes", StickerCategory.EYEWEAR, Anchor.EYES, 1.2f, offsetY = -0.02f, rot = RotationMode.HEAD_3D, aspect = 2.4f),
        a("hero_mask", "Hero mask", StickerCategory.EYEWEAR, Anchor.EYES, 1.3f, rot = RotationMode.HEAD_3D, aspect = 2.4f),
        // ---- Facial hair ----
        a("mustache_handlebar", "Handlebar mustache", StickerCategory.FACIAL_HAIR, Anchor.UPPER_LIP, 0.7f, rot = RotationMode.HEAD_3D, aspect = 2.4f),
        a("mustache_chevron", "Chevron mustache", StickerCategory.FACIAL_HAIR, Anchor.UPPER_LIP, 0.55f, rot = RotationMode.HEAD_3D, aspect = 2.4f),
        a("goatee", "Goatee", StickerCategory.FACIAL_HAIR, Anchor.CHIN, 0.45f, offsetY = -0.02f, rot = RotationMode.HEAD_3D),
        a("beard_full", "Full beard", StickerCategory.FACIAL_HAIR, Anchor.CHIN, 1.15f, offsetY = -0.12f, rot = RotationMode.HEAD_3D),
        a("beard_wizard", "Wizard beard", StickerCategory.FACIAL_HAIR, Anchor.CHIN, 1.1f, offsetY = 0.45f, rot = RotationMode.HEAD_3D),
        a("beard_pirate", "Pirate beard", StickerCategory.FACIAL_HAIR, Anchor.CHIN, 1.1f, offsetY = 0.1f, rot = RotationMode.HEAD_3D),
        // ---- Faces & noses ----
        a("clown_nose", "Clown nose", StickerCategory.FACE, Anchor.NOSE, 0.3f, rot = RotationMode.HEAD_3D, offsetZ = 0.1f),
        a("pig_nose", "Pig nose", StickerCategory.FACE, Anchor.NOSE, 0.4f, offsetY = 0.02f, rot = RotationMode.HEAD_3D),
        a("dog_nose", "Dog nose & tongue", StickerCategory.FACE, Anchor.NOSE, 0.5f, offsetY = 0.2f, rot = RotationMode.HEAD_3D),
        a("cat_nose", "Cat nose & whiskers", StickerCategory.FACE, Anchor.NOSE, 1.2f, offsetY = 0.05f, rot = RotationMode.HEAD_3D, aspect = 2.4f),
        a("skull_mask", "Skull", StickerCategory.FACE, Anchor.FACE_FULL, 1.2f, offsetY = -0.05f, rot = RotationMode.HEAD_3D),
        a("robot_faceplate", "Robot faceplate", StickerCategory.FACE, Anchor.FACE_FULL, 1.25f, rot = RotationMode.HEAD_3D),
        a("astronaut_helmet", "Astronaut helmet", StickerCategory.FACE, Anchor.FACE_CENTER, 2.3f, offsetY = -0.1f, rot = RotationMode.BILLBOARD),
        a("vampire_fangs", "Vampire fangs", StickerCategory.FACE, Anchor.MOUTH, 0.35f, offsetY = 0.05f, rot = RotationMode.HEAD_3D),
        a("bandit_mask", "Bandit bandana", StickerCategory.FACE, Anchor.MOUTH, 1.2f, offsetY = 0.1f, rot = RotationMode.HEAD_3D),
        // ---- Costume pieces (neck / body) ----
        a("bow_tie", "Bow tie", StickerCategory.COSTUME, Anchor.NECK, 0.7f, offsetY = 0.05f, aspect = 2.4f),
        a("neck_tie", "Necktie", StickerCategory.COSTUME, Anchor.NECK, 0.35f, offsetY = 0.55f, aspect = 0.4f),
        a("police_badge", "Police badge", StickerCategory.COSTUME, Anchor.LEFT_SHOULDER, 0.45f, offsetY = 0.35f, offsetX = 0.25f),
        a("stethoscope", "Stethoscope", StickerCategory.COSTUME, Anchor.NECK, 1.4f, offsetY = 0.35f),
        a("astronaut_collar", "Space suit collar", StickerCategory.COSTUME, Anchor.NECK, 2.6f, offsetY = 0.55f, aspect = 2.4f),
        a("hero_logo", "Hero emblem", StickerCategory.COSTUME, Anchor.CHEST, 0.7f),
        a("lei", "Flower lei", StickerCategory.COSTUME, Anchor.NECK, 1.5f, offsetY = 0.35f),
        a("scarf", "Scarf", StickerCategory.COSTUME, Anchor.NECK, 1.4f, offsetY = 0.3f),
        a("gold_chain", "Gold chain", StickerCategory.COSTUME, Anchor.NECK, 1.3f, offsetY = 0.3f),
        a("lab_coat_collar", "Doctor's coat", StickerCategory.COSTUME, Anchor.NECK, 2.5f, offsetY = 0.6f, aspect = 2.4f),
        // ---- Behind the person ----
        a("cape", "Hero cape", StickerCategory.BEHIND, Anchor.BEHIND_BODY, 3.0f, offsetY = 0.9f, behind = true),
        a("angel_wings", "Angel wings", StickerCategory.BEHIND, Anchor.BEHIND_BODY, 3.4f, offsetY = 0.1f, behind = true, aspect = 2.4f),
        a("bat_wings", "Bat wings", StickerCategory.BEHIND, Anchor.BEHIND_BODY, 3.4f, offsetY = 0.1f, behind = true, aspect = 2.4f),
        a("sun_rays", "Sun rays", StickerCategory.BEHIND, Anchor.BEHIND_HEAD, 3.2f, behind = true, spin = 6f, rot = RotationMode.NONE),
        // ---- Accessories ----
        a("earring_hoop", "Hoop earring", StickerCategory.ACCESSORY, Anchor.LEFT_EAR, 0.16f, offsetY = 0.18f, rot = RotationMode.NONE),
        a("speech_bubble", "Speech bubble", StickerCategory.ACCESSORY, Anchor.HEAD_TOP, 1.0f, offsetX = 0.9f, offsetY = -0.5f, rot = RotationMode.NONE),
        a("thought_bubble", "Thought bubble", StickerCategory.ACCESSORY, Anchor.HEAD_TOP, 1.0f, offsetX = 0.9f, offsetY = -0.6f, rot = RotationMode.NONE),
        a("butterfly", "Butterfly", StickerCategory.ACCESSORY, Anchor.HEAD_TOP, 0.4f, offsetX = 0.45f, offsetY = -0.1f, rot = RotationMode.NONE, pulse = 0.1f),
        a("hearts", "Floating hearts", StickerCategory.ACCESSORY, Anchor.HEAD_TOP, 1.4f, offsetY = -0.55f, rot = RotationMode.NONE, pulse = 0.15f),
        a("rec_badge", "REC badge", StickerCategory.FRAME, Anchor.FRAME_TOP_LEFT, 0.28f, offsetX = 0.2f, offsetY = 0.12f, rot = RotationMode.NONE, pulse = 0.06f, aspect = 2.4f),
        a("live_badge", "LIVE badge", StickerCategory.FRAME, Anchor.FRAME_TOP_RIGHT, 0.28f, offsetX = -0.2f, offsetY = 0.12f, rot = RotationMode.NONE, aspect = 2.4f),
        a("film_frame", "Film frame corners", StickerCategory.FRAME, Anchor.FRAME_CENTER, 1.0f, rot = RotationMode.NONE),
    )

    fun sticker(id: String): StickerAsset? = stickers.firstOrNull { it.id == id }

    val proceduralBackgrounds: List<ProceduralBackground> = listOf(
        ProceduralBackground("hyperspace", "Hyperspace", 0, "🚀"), ProceduralBackground("nebula", "Nebula", 1, "🌌"), ProceduralBackground("aurora", "Aurora", 2, "🌃"),
        ProceduralBackground("ocean_sunset", "Ocean sunset", 3, "🌅"), ProceduralBackground("cyber_grid", "Cyber grid", 4, "🟪"), ProceduralBackground("matrix", "Digital rain", 5, "💻"),
        ProceduralBackground("disco", "Disco lights", 6, "🪩"), ProceduralBackground("clouds", "Clouds", 7, "☁️"), ProceduralBackground("underwater", "Underwater", 8, "🐠"),
        ProceduralBackground("lava", "Lava lamp", 9, "🧪"), ProceduralBackground("snow", "Snowfall", 10, "❄️"), ProceduralBackground("fireflies", "Fireflies", 11, "✨"),
        ProceduralBackground("gradient_wave", "Gradient wave", 12, "🌈"), ProceduralBackground("bokeh", "Bokeh lights", 13, "🔆"), ProceduralBackground("retro_sun", "Retro sun", 14, "🌇"),
        ProceduralBackground("checker_room", "Checker room", 15, "🏁"),
    )

    val parallaxScenes: List<ParallaxScene> = listOf(
        ParallaxScene("mountains", "Mountain lake", "🏔️", listOf(ParallaxLayer("bg_mountains_far", 0.1f), ParallaxLayer("bg_mountains_mid", 0.45f), ParallaxLayer("bg_mountains_near", 1.0f, 1.25f))),
        ParallaxScene("city_night", "City at night", "🌃", listOf(ParallaxLayer("bg_city_sky", 0.1f), ParallaxLayer("bg_city_far", 0.45f), ParallaxLayer("bg_city_near", 1.0f, 1.25f))),
        ParallaxScene("space_station", "Space station", "🛰️", listOf(ParallaxLayer("bg_space_stars", 0.05f), ParallaxLayer("bg_space_planet", 0.35f), ParallaxLayer("bg_space_window", 1.0f, 1.2f))),
        ParallaxScene("library", "Library", "📚", listOf(ParallaxLayer("bg_library_wall", 0.1f), ParallaxLayer("bg_library_shelves", 0.45f), ParallaxLayer("bg_library_desk", 1.0f, 1.25f))),
        ParallaxScene("beach", "Beach", "🏖️", listOf(ParallaxLayer("bg_beach_sky", 0.1f), ParallaxLayer("bg_beach_sea", 0.45f), ParallaxLayer("bg_beach_palm", 1.0f, 1.25f))),
        ParallaxScene("office", "Modern office", "🏢", listOf(ParallaxLayer("bg_office_wall", 0.1f), ParallaxLayer("bg_office_mid", 0.45f), ParallaxLayer("bg_office_desk", 1.0f, 1.25f))),
    )

    private fun withStickers(s: EffectsSettings, vararg ids: String): EffectsSettings =
        s.copy(stickers = ids.mapNotNull { id -> sticker(id)?.layer() })

    private fun bg(type: BackgroundType, id: String? = null) = BackgroundSpec(type = type, id = id)

    /** Preset looks. Each replaces the stack (background, stickers, face/age/look/fun) but keeps beauty sliders unless it sets them. */
    val looks: List<EffectLook> = listOf(
        EffectLook("cop", "Cop", "👮", "Characters") { s -> withStickers(s.cleared(), "cop_hat", "aviators", "police_badge", "neck_tie") },
        EffectLook("wizard", "Wizard", "🧙", "Characters") { s -> withStickers(s.cleared(), "wizard_hat", "beard_wizard", "sparkles").copy(background = bg(BackgroundType.PROCEDURAL, "nebula")) },
        EffectLook("alien", "Alien", "👽", "Characters") { s -> withStickers(s.cleared(), "alien_antennae", "alien_eyes").copy(faceMode = FaceMode.ALIEN, funMode = FunMode.LONG_FACE, funIntensity = 0.45f, background = bg(BackgroundType.PROCEDURAL, "hyperspace")) },
        EffectLook("astronaut", "Astronaut", "🧑‍🚀", "Characters") { s -> withStickers(s.cleared(), "astronaut_helmet", "astronaut_collar").copy(background = bg(BackgroundType.PARALLAX, "space_station")) },
        EffectLook("pirate", "Pirate", "🏴‍☠️", "Characters") { s -> withStickers(s.cleared(), "pirate_hat", "eye_patch", "beard_pirate", "earring_hoop").copy(background = bg(BackgroundType.PROCEDURAL, "ocean_sunset")) },
        EffectLook("zombie", "Zombie", "🧟", "Characters") { s -> s.cleared().copy(faceMode = FaceMode.ZOMBIE, look = ColorLook.FADED, lookIntensity = 0.7f, vignette = 0.4f) },
        EffectLook("vampire", "Vampire", "🧛", "Characters") { s -> withStickers(s.cleared(), "vampire_fangs", "bat_wings").copy(faceMode = FaceMode.VAMPIRE, look = ColorLook.MOONLIGHT, lookIntensity = 0.6f) },
        EffectLook("robot", "Robot", "🤖", "Characters") { s -> withStickers(s.cleared(), "robot_faceplate", "cyborg_eye").copy(faceMode = FaceMode.ROBOT, background = bg(BackgroundType.PROCEDURAL, "cyber_grid")) },
        EffectLook("superhero", "Superhero", "🦸", "Characters") { s -> withStickers(s.cleared(), "hero_mask", "cape", "hero_logo") },
        EffectLook("clown", "Clown", "🤡", "Characters") { s -> withStickers(s.cleared(), "clown_nose", "party_hat").copy(faceMode = FaceMode.CLOWN) },
        EffectLook("royalty", "Royalty", "👑", "Characters") { s -> withStickers(s.cleared(), "crown", "gold_chain").copy(look = ColorLook.VIVID, lookIntensity = 0.5f) },
        EffectLook("cat", "Cat", "🐱", "Animals") { s -> withStickers(s.cleared(), "cat_ears", "cat_nose") },
        EffectLook("dog", "Dog", "🐶", "Animals") { s -> withStickers(s.cleared(), "dog_ears", "dog_nose") },
        EffectLook("bunny", "Bunny", "🐰", "Animals") { s -> withStickers(s.cleared(), "bunny_ears", "pig_nose").copy(beauty = s.beauty.copy(blush = 0.5f)) },
        EffectLook("angel", "Angel", "😇", "Characters") { s -> withStickers(s.cleared(), "halo", "angel_wings").copy(look = ColorLook.PASTEL, lookIntensity = 0.5f, beauty = s.beauty.copy(glow = 0.5f)) },
        EffectLook("devil", "Devil", "😈", "Characters") { s -> withStickers(s.cleared(), "devil_horns", "bat_wings").copy(look = ColorLook.CROSS_PROCESS, lookIntensity = 0.5f) },
        EffectLook("doctor", "Doctor", "🩺", "Characters") { s -> withStickers(s.cleared(), "lab_coat_collar", "stethoscope") },
        EffectLook("chef", "Chef", "👨‍🍳", "Characters") { s -> withStickers(s.cleared(), "chef_hat", "mustache_chevron") },
        EffectLook("viking", "Viking", "🪓", "Characters") { s -> withStickers(s.cleared(), "viking_helmet", "beard_full") },
        EffectLook("cowboy", "Cowboy", "🤠", "Characters") { s -> withStickers(s.cleared(), "cowboy_hat", "mustache_handlebar", "bandit_mask") },
        EffectLook("hawaii", "Beach day", "🌺", "Scenes") { s -> withStickers(s.cleared(), "lei", "star_glasses").copy(background = bg(BackgroundType.PARALLAX, "beach"), look = ColorLook.GOLDEN_HOUR, lookIntensity = 0.5f) },
        EffectLook("office", "Office", "🏢", "Scenes") { s -> s.cleared().copy(background = bg(BackgroundType.PARALLAX, "office")) },
        EffectLook("library", "Library", "📚", "Scenes") { s -> s.cleared().copy(background = bg(BackgroundType.PARALLAX, "library")) },
        EffectLook("mountains", "Mountains", "🏔️", "Scenes") { s -> s.cleared().copy(background = bg(BackgroundType.PARALLAX, "mountains")) },
        EffectLook("city", "City night", "🌃", "Scenes") { s -> s.cleared().copy(background = bg(BackgroundType.PARALLAX, "city_night"), look = ColorLook.CYBERPUNK, lookIntensity = 0.35f) },
        EffectLook("blur", "Blur background", "🌫️", "Scenes") { s -> s.cleared().copy(background = BackgroundSpec(BackgroundType.BLUR, blur = 0.7f)) },
        EffectLook("older", "Older", "🧓", "Age") { s -> s.cleared().copy(age = AgeMode.OLDER, ageIntensity = 0.8f) },
        EffectLook("much_older", "Much older", "👴", "Age") { s -> s.cleared().copy(age = AgeMode.MUCH_OLDER, ageIntensity = 1f, look = ColorLook.FADED, lookIntensity = 0.3f) },
        EffectLook("younger", "Younger", "🧒", "Age") { s -> s.cleared().copy(age = AgeMode.YOUNGER, ageIntensity = 0.8f) },
        EffectLook("baby", "Baby face", "👶", "Age") { s -> s.cleared().copy(age = AgeMode.BABY, ageIntensity = 0.9f) },
        EffectLook("glam", "Glam", "💄", "Beauty") { s -> s.cleared().copy(beauty = BeautySettings(smoothing = 0.6f, brightening = 0.2f, eyeEnlarge = 0.15f, faceSlim = 0.15f, teethWhitening = 0.4f, lipstick = 0.6f, blush = 0.3f, eyeBrighten = 0.3f, glow = 0.2f), look = ColorLook.WARM, lookIntensity = 0.4f) },
        EffectLook("natural", "Natural touch-up", "✨", "Beauty") { s -> s.cleared().copy(beauty = BeautySettings(smoothing = 0.35f, brightening = 0.1f, eyeBrighten = 0.15f, teethWhitening = 0.2f)) },
        EffectLook("vintage", "Vintage film", "🎞️", "Color") { s -> s.cleared().copy(look = ColorLook.VINTAGE, grain = 0.4f, vignette = 0.5f) },
        EffectLook("noir", "Noir", "🎩", "Color") { s -> s.cleared().copy(look = ColorLook.NOIR, vignette = 0.6f, grain = 0.25f) },
        EffectLook("cyberpunk", "Cyberpunk", "🟣", "Color") { s -> s.cleared().copy(look = ColorLook.CYBERPUNK, background = bg(BackgroundType.PROCEDURAL, "cyber_grid")) },
        EffectLook("teal_orange", "Blockbuster", "🎬", "Color") { s -> s.cleared().copy(look = ColorLook.TEAL_ORANGE, lookIntensity = 0.8f) },
        EffectLook("big_head", "Big head", "🎈", "Fun") { s -> s.cleared().copy(funMode = FunMode.BIG_HEAD, funIntensity = 0.7f) },
        EffectLook("tiny_head", "Tiny head", "🫠", "Fun") { s -> s.cleared().copy(funMode = FunMode.TINY_HEAD, funIntensity = 0.7f) },
        EffectLook("cartoon", "Cartoon", "🖍️", "Fun") { s -> s.cleared().copy(funMode = FunMode.CARTOON, funIntensity = 0.8f) },
        EffectLook("thermal", "Thermal cam", "🌡️", "Fun") { s -> s.cleared().copy(funMode = FunMode.THERMAL) },
        EffectLook("vhs", "VHS", "📼", "Fun") { s -> s.cleared().copy(funMode = FunMode.VHS, funIntensity = 0.8f) },
        EffectLook("glitch", "Glitch", "🪞", "Fun") { s -> s.cleared().copy(funMode = FunMode.GLITCH, funIntensity = 0.7f) },
        EffectLook("sketch", "Pencil sketch", "✏️", "Fun") { s -> s.cleared().copy(funMode = FunMode.SKETCH, funIntensity = 0.9f) },
        EffectLook("halftone", "Comic", "💥", "Fun") { s -> s.cleared().copy(funMode = FunMode.HALFTONE, funIntensity = 0.8f) },
        EffectLook("mirror", "Mirror", "🪞", "Fun") { s -> s.cleared().copy(funMode = FunMode.MIRROR) },
        EffectLook("kaleido", "Kaleidoscope", "🔮", "Fun") { s -> s.cleared().copy(funMode = FunMode.KALEIDOSCOPE, funIntensity = 0.8f) },
    )

    fun look(id: String): EffectLook? = looks.firstOrNull { it.id == id }
}

/** Remove everything but the technical settings (render resolution, debug flips, user assets). */
fun EffectsSettings.cleared(): EffectsSettings = copy(
    background = BackgroundSpec(), stickers = emptyList(), faceMode = FaceMode.NONE, age = AgeMode.NONE, look = ColorLook.NONE,
    funMode = FunMode.NONE, beauty = BeautySettings(), vignette = 0f, grain = 0f, activeLook = null,
)
