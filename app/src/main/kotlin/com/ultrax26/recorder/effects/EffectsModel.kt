package com.ultrax26.recorder.effects

import kotlinx.serialization.Serializable

/** Where a sticker attaches. Face anchors come from the 478-point face mesh, body anchors from pose. */
@Serializable
enum class Anchor(val label: String, val body: Boolean = false, val frame: Boolean = false) {
    HEAD_TOP("Top of head"), FOREHEAD("Forehead"), BROWS("Eyebrows"), EYES("Eyes"), LEFT_EYE("Left eye"), RIGHT_EYE("Right eye"),
    NOSE_BRIDGE("Nose bridge"), NOSE("Nose"), UPPER_LIP("Upper lip"), MOUTH("Mouth"), CHIN("Chin"),
    LEFT_EAR("Left ear"), RIGHT_EAR("Right ear"), LEFT_CHEEK("Left cheek"), RIGHT_CHEEK("Right cheek"),
    FACE_CENTER("Face center"), FACE_FULL("Whole face"), NECK("Neck"),
    CHEST("Chest", body = true), LEFT_SHOULDER("Left shoulder", body = true), RIGHT_SHOULDER("Right shoulder", body = true), TORSO("Torso", body = true),
    BEHIND_HEAD("Behind head"), BEHIND_BODY("Behind body", body = true),
    FRAME_TOP_LEFT("Frame top-left", frame = true), FRAME_TOP_RIGHT("Frame top-right", frame = true),
    FRAME_BOTTOM_LEFT("Frame bottom-left", frame = true), FRAME_BOTTOM_RIGHT("Frame bottom-right", frame = true), FRAME_CENTER("Frame center", frame = true),
}

@Serializable
enum class RotationMode(val label: String) { BILLBOARD("Follow head roll"), HEAD_3D("Full 3D head pose"), NONE("Fixed") }

@Serializable
enum class StickerCategory(val label: String) {
    HEADWEAR("Hats & ears"), EYEWEAR("Glasses"), FACIAL_HAIR("Mustaches & beards"), FACE("Faces & noses"),
    COSTUME("Costumes"), ACCESSORY("Accessories"), BEHIND("Wings & capes"), FRAME("Frame stickers"), USER("My stickers"),
}

/** One placed sticker instance. */
@Serializable
data class StickerLayer(
    val id: String,
    val assetId: String,
    val anchor: Anchor,
    val scale: Float = 1f,
    val offsetX: Float = 0f,        // in face-width units (or frame-height units for frame anchors)
    val offsetY: Float = 0f,
    val offsetZ: Float = 0f,        // depth toward the camera (3D mode), face-width units
    val rotation: RotationMode = RotationMode.BILLBOARD,
    val behindPerson: Boolean = false,
    val userUri: String? = null,    // imported PNG instead of a built-in drawable
    val spinDegPerSec: Float = 0f,
    val pulse: Float = 0f,          // 0..1 breathing scale animation
    val opacity: Float = 1f,
    val mirrorX: Boolean = false,
)

@Serializable
enum class BackgroundType(val label: String) {
    NONE("Real background"), BLUR("Blur"), COLOR("Solid color"), PROCEDURAL("Animated scene"), IMAGE("Photo"), VIDEO("Video"), PARALLAX("3D parallax scene"),
}

@Serializable
data class BackgroundSpec(
    val type: BackgroundType = BackgroundType.NONE,
    val id: String? = null,         // procedural / parallax scene id
    val uri: String? = null,        // user image or video
    val blur: Float = 0.6f,         // 0..1
    val color: Long = 0xFF0EA5E9,
    val feather: Float = 0.5f,      // mask edge softness
    val edgeShift: Float = 0f,      // -1..1 shrink/grow the person mask
    val parallaxStrength: Float = 1f,
    val lightWrap: Float = 0.25f,   // blend background color into the person's edge
    val animate: Boolean = true,
)

@Serializable
data class BeautySettings(
    val smoothing: Float = 0f,
    val brightening: Float = 0f,
    val eyeEnlarge: Float = 0f,
    val faceSlim: Float = 0f,
    val noseSlim: Float = 0f,
    val chinShorten: Float = 0f,
    val teethWhitening: Float = 0f,
    val lipstick: Float = 0f,
    val lipColor: Long = 0xFFC0264B,
    val blush: Float = 0f,
    val blushColor: Long = 0xFFF28B9B,
    val eyeBrighten: Float = 0f,
    val sharpen: Float = 0f,
    val glow: Float = 0f,
) {
    fun any(): Boolean = smoothing > 0f || brightening > 0f || eyeEnlarge > 0f || faceSlim > 0f || noseSlim > 0f || chinShorten > 0f ||
        teethWhitening > 0f || lipstick > 0f || blush > 0f || eyeBrighten > 0f || sharpen > 0f || glow > 0f
}

/** Whole-face treatments implemented as masked shader passes. */
@Serializable
enum class FaceMode(val label: String) {
    NONE("None"), ALIEN("Alien"), ZOMBIE("Zombie"), ROBOT("Robot"), CLOWN("Clown"), GHOST("Ghost"),
    VAMPIRE("Vampire"), GOLD("Gold statue"), STONE("Stone"), SMURF("Blue creature"), HULK("Green giant"), FROZEN("Frozen"),
}

/** Age looks are stylized approximations (texture, tone and warp), not learned face aging. */
@Serializable
enum class AgeMode(val label: String) { NONE("None"), OLDER("Older"), MUCH_OLDER("Much older"), YOUNGER("Younger"), BABY("Baby face") }

@Serializable
enum class ColorLook(val label: String) {
    NONE("None"), WARM("Warm"), COOL("Cool"), TEAL_ORANGE("Teal & orange"), BW("Black & white"), SEPIA("Sepia"), VINTAGE("Vintage film"),
    FADED("Faded"), MATTE("Matte"), VIVID("Vivid"), NOIR("Noir"), CROSS_PROCESS("Cross process"), CYBERPUNK("Cyberpunk"), PASTEL("Pastel"),
    GOLDEN_HOUR("Golden hour"), MOONLIGHT("Moonlight"),
}

@Serializable
enum class FunMode(val label: String) {
    NONE("None"), BIG_HEAD("Big head"), TINY_HEAD("Tiny head"), LONG_FACE("Long face"), WIDE_FACE("Wide face"), FISHEYE("Fisheye"),
    MIRROR("Mirror"), PIXEL_FACE("Pixel face"), PIXELATE("Pixelate all"), CARTOON("Cartoon"), THERMAL("Thermal"), NEGATIVE("Negative"),
    VHS("VHS tape"), GLITCH("Glitch"), HALFTONE("Comic halftone"), SKETCH("Pencil sketch"), NIGHT_VISION("Night vision"), RAINBOW("Rainbow hue"),
    KALEIDOSCOPE("Kaleidoscope"), OIL_PAINT("Oil paint"), SWIRL("Swirl"),
}

@Serializable
enum class RenderRes(val label: String, val maxWidth: Int) { P1080("1080p (fastest)", 1920), P1440("1440p", 2560), UHD4K("4K", 3840), MATCH("Match recording (may drop frames at 8K)", 0) }

@Serializable
data class UserAsset(val id: String, val name: String, val uri: String, val isVideo: Boolean = false)

/** Everything in the effects stack. Persisted with the rest of AppSettings. */
@Serializable
data class EffectsSettings(
    val background: BackgroundSpec = BackgroundSpec(),
    val stickers: List<StickerLayer> = emptyList(),
    val faceMode: FaceMode = FaceMode.NONE,
    val faceModeIntensity: Float = 1f,
    val beauty: BeautySettings = BeautySettings(),
    val age: AgeMode = AgeMode.NONE,
    val ageIntensity: Float = 0.8f,
    val look: ColorLook = ColorLook.NONE,
    val lookIntensity: Float = 1f,
    val funMode: FunMode = FunMode.NONE,
    val funIntensity: Float = 0.7f,
    val vignette: Float = 0f,
    val grain: Float = 0f,
    val renderRes: RenderRes = RenderRes.UHD4K,
    val headParallax: Boolean = true,
    val forcePipeline: Boolean = false,   // keep the GL pipeline on even with no active effect (instant toggling while recording)
    val debugMesh: Boolean = false,
    val flipCameraY: Boolean = false,
    val flipMaskY: Boolean = false,
    val invertYaw: Boolean = false,
    val invertPitch: Boolean = false,
    val activeLook: String? = null,
    val userStickers: List<UserAsset> = emptyList(),
    val userBackgrounds: List<UserAsset> = emptyList(),
    val useMultiClassSegmenter: Boolean = true,
    val poseTracking: Boolean = true,
) {
    fun isActive(): Boolean = background.type != BackgroundType.NONE || stickers.isNotEmpty() || faceMode != FaceMode.NONE ||
        age != AgeMode.NONE || look != ColorLook.NONE || funMode != FunMode.NONE || beauty.any() || vignette > 0f || grain > 0f
    fun needsPipeline(): Boolean = forcePipeline || isActive()
    fun needsFaceMesh(): Boolean = stickers.any { !it.anchor.frame } || faceMode != FaceMode.NONE || age != AgeMode.NONE || beauty.any() ||
        funMode in setOf(FunMode.BIG_HEAD, FunMode.TINY_HEAD, FunMode.LONG_FACE, FunMode.WIDE_FACE, FunMode.PIXEL_FACE, FunMode.SWIRL) ||
        (background.type == BackgroundType.PARALLAX && headParallax)
    fun needsSegmentation(): Boolean = background.type != BackgroundType.NONE || stickers.any { it.behindPerson } ||
        age != AgeMode.NONE || faceMode in setOf(FaceMode.CLOWN, FaceMode.GHOST, FaceMode.FROZEN)
    fun needsPose(): Boolean = poseTracking && stickers.any { it.anchor.body }
}
