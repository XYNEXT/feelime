plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.feelime.ime.nativeengine"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.feelime.ime.nativeengine"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

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

    packaging { jniLibs { useLegacyPackaging = true } }

    sourceSets {
        getByName("main") {
            // arm64 引擎库与词典数据复用 app 的入库副本（避免同字节存两份）；
            // x86_64 库只有 spike 自有，仍在 src/main/jniLibs/x86_64。
            jniLibs.srcDir("../../../app/src/main/jniLibs")
            assets.srcDir("../../../app/src/main/assets")
        }
    }
    androidResources { noCompress += listOf("bin", "data", "aff", "dic") }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
