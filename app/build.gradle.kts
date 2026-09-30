import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// ---------------------------------------------------------------------------------------------
// MediaPipe gesture model: downloaded at build time (not committed) and verified by SHA-256.
// Source: https://ai.google.dev/edge/mediapipe/solutions/vision/gesture_recognizer
// ---------------------------------------------------------------------------------------------
val gestureModelUrl =
    "https://storage.googleapis.com/mediapipe-models/gesture_recognizer/gesture_recognizer/float16/latest/gesture_recognizer.task"
val gestureModelSha256 = "97952348cf6a6a4915c2ea1496b4b37ebabc50cbbf80571435643c455f2b0482"
val generatedAssetsDir = layout.buildDirectory.dir("generated/ultrax-assets")

val downloadGestureModel by tasks.registering {
    val outFile = generatedAssetsDir.map { it.file("gesture_recognizer.task") }
    outputs.file(outFile)
    outputs.cacheIf { true }
    doLast {
        val target = outFile.get().asFile
        fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { ins ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = ins.read(buf); if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
        if (target.exists() && sha256(target) == gestureModelSha256) return@doLast
        target.parentFile.mkdirs()
        logger.lifecycle("Downloading MediaPipe gesture model -> ${target.path}")
        URI(gestureModelUrl).toURL().openStream().use { input -> target.outputStream().use { input.copyTo(it) } }
        val actual = sha256(target)
        check(actual == gestureModelSha256) {
            target.delete(); "gesture_recognizer.task checksum mismatch: expected $gestureModelSha256, got $actual"
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
