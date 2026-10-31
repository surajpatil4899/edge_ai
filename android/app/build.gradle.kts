plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.edgeai.assistant"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "com.edgeai.assistant"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        // arm64 for phones, x86_64 for the emulator; 32-bit ABIs would add ~45 MB for little benefit.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures { compose = true }

    // Models are read straight from the APK; compressing them only slows startup.
    androidResources { noCompress += listOf("onnx", "ort") }

    // abiFilters drops 32-bit code, but both native AARs still carry a 32-bit libonnxruntime.so that collide at merge.
    packaging { jniLibs { excludes += listOf("lib/x86/**", "lib/armeabi-v7a/**") } }
}

dependencies {
    // sherpa-onnx build with ONNX Runtime linked in privately (scripts/download_models.sh fetches it). The
    // regular AAR ships a libonnxruntime.so whose symbol versions (VERS_1.28.2) clash with onnxruntime-android.
    implementation(files("libs/sherpa-onnx-static-link-onnxruntime-2.0.1.aar"))
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.28.0")

    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.core:core-ktx:1.19.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}

// Model files live in the repo-level models/ dir (downloaded by scripts/download_models.sh)
// and are copied into the APK's assets at build time; they are too big to commit.
val modelsDir = rootProject.layout.projectDirectory.dir("../models")
val copyModels by tasks.registering(Sync::class) {
    from(modelsDir.dir("sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01")) {
        include("*.int8.onnx", "tokens.txt")
        into("kws")
    }
    from(modelsDir.dir("sherpa-onnx-moonshine-base-en-quantized-2026-02-27")) {
        include("encoder_model.ort", "decoder_model_merged.ort", "tokens.txt")
        into("asr")
    }
    from(modelsDir) {
        include("silero_vad.onnx")
        into("vad")
    }
    from(modelsDir.dir("openwakeword")) {
        include("melspectrogram.onnx", "embedding_model.onnx")
        into("oww")
    }
    into(layout.projectDirectory.dir("src/main/assets/models"))
    // Plain File so the configuration cache can serialize the action (script references can't be).
    val asrDir = modelsDir.dir("sherpa-onnx-moonshine-base-en-quantized-2026-02-27").asFile
    doFirst {
        check(asrDir.isDirectory) {
            "Models missing: run scripts/download_models.sh from the repo root first"
        }
    }
}
tasks.named("preBuild") { dependsOn(copyModels) }
