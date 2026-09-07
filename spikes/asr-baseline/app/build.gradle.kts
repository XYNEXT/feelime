plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.feelime.ime.spike"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.feelime.ime.asrbaseline"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    // The ASR models live outside app assets, in
    // src/modelAssets/full (thin-build prep) - the spike shares BOTH.
    sourceSets.getByName("main").assets.srcDirs(
        "../../../app/src/main/assets",
        "../../../app/src/modelAssets/full",
    )

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug").apply {
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    androidResources { noCompress += listOf("onnx", "txt", "vocab") }
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    implementation(files("../../../app/libs/sherpa-onnx-1.13.6.aar"))
}
