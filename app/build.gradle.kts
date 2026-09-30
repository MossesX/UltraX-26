import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// ---------------------------------------------------------------------------------------------
// MediaPipe models: downloaded at build time (not committed) and verified by SHA-256.
// https://ai.google.dev/edge/mediapipe/solutions/vision
// ---------------------------------------------------------------------------------------------
data class MlModel(val file: String, val url: String, val sha256: String)
val mlModels = listOf(
    MlModel("gesture_recognizer.task", "https://storage.googleapis.com/mediapipe-models/gesture_recognizer/gesture_recognizer/float16/latest/gesture_recognizer.task", "97952348cf6a6a4915c2ea1496b4b37ebabc50cbbf80571435643c455f2b0482"),
    MlModel("face_landmarker.task", "https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/latest/face_landmarker.task", "64184e229b263107bc2b804c6625db1341ff2bb731874b0bcc2fe6544e0bc9ff"),
    MlModel("selfie_multiclass_256x256.tflite", "https://storage.googleapis.com/mediapipe-models/image_segmenter/selfie_multiclass_256x256/float32/latest/selfie_multiclass_256x256.tflite", "c6748b1253a99067ef71f7e26ca71096cd449baefa8f101900ea23016507e0e0"),
    MlModel("selfie_segmenter.tflite", "https://storage.googleapis.com/mediapipe-models/image_segmenter/selfie_segmenter/float16/latest/selfie_segmenter.tflite", "191ac9529ae506ee0beefa6b2c945a172dab9d07d1e802a290a4e4038226658b"),
    MlModel("pose_landmarker_lite.task", "https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/latest/pose_landmarker_lite.task", "59929e1d1ee95287735ddd833b19cf4ac46d29bc7afddbbf6753c459690d574a"),
)
val generatedAssetsDir = layout.buildDirectory.dir("generated/ultrax-assets")

val downloadGestureModel by tasks.registering {
    val outDir = generatedAssetsDir
    val models = mlModels.map { listOf(it.file, it.url, it.sha256) }
    outputs.dir(outDir)
    outputs.cacheIf { true }
    doLast {
        fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { ins -> val buf = ByteArray(1 shl 16); while (true) { val n = ins.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
        val dir = outDir.get().asFile.also { it.mkdirs() }
        for ((file, url, sha) in models) {
            val target = File(dir, file)
            if (target.exists() && sha256(target) == sha) continue
            logger.lifecycle("Downloading MediaPipe model $file")
            URI(url).toURL().openStream().use { input -> target.outputStream().use { input.copyTo(it) } }
            val actual = sha256(target)
            check(actual == sha) { target.delete(); "$file checksum mismatch: expected $sha, got $actual" }
        }
    }
}

android {
    namespace = "com.ultrax26.recorder"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ultrax26.recorder"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        // Optional release keystore from environment (CI secrets). Falls back to the debug key so a
        // minified release APK is still installable on a test device.
        val ksPath = System.getenv("ULTRAX_KEYSTORE_PATH")
        if (!ksPath.isNullOrBlank() && file(ksPath).exists()) {
            create("release") {
                storeFile = file(ksPath)
                storePassword = System.getenv("ULTRAX_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ULTRAX_KEY_ALIAS")
                keyPassword = System.getenv("ULTRAX_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/*.kotlin_module")
        // The MediaPipe model is large; keep it uncompressed so it can be memory-mapped.
        jniLibs.useLegacyPackaging = false
    }
    androidResources {
        noCompress += listOf("task", "tflite")
    }
    sourceSets {
        getByName("main") {
            kotlin.srcDirs("src/main/kotlin")
            assets.srcDirs(generatedAssetsDir.get().asFile)
        }
        getByName("test") { kotlin.srcDirs("src/test/kotlin") }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

// Make sure the model exists before any asset task (generate*/merge*/package*Assets) runs.
tasks.matching { it.name.endsWith("Assets") }.configureEach { dependsOn(downloadGestureModel) }

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.add("-opt-in=kotlin.RequiresOptIn")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.serialization.json)

    // Vision: hand gestures (MediaPipe Tasks) + face/eye/head signals (ML Kit, bundled model).
    implementation(libs.mediapipe.tasks.vision)
    implementation(libs.mlkit.face.detection)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
