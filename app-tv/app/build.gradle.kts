plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
fun env(name: String) = (System.getenv(name) ?: "").trim()

android {
    namespace = "br.reels.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "br.reels.tv"
        minSdk = 24
        targetSdk = 35
        versionCode = runNumber
        versionName = "0.2.$runNumber"
        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"${env("FIREBASE_PROJECT_ID")}\"")
        buildConfigField("String", "FIREBASE_API_KEY", "\"${env("FIREBASE_API_KEY")}\"")
        // Fire Stick e TVs Android usam ARM
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
    }

    signingConfigs {
        // Chave fixa: assim cada nova versão instala "por cima" da anterior sem perder o pareamento.
        create("release") {
            storeFile = file("../keystore/reels-tv.jks")
            storePassword = "reelstv"
            keyAlias = "reelstv"
            keyPassword = "reelstv"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // Necessário para o Python/yt-dlp embutido
    packaging { jniLibs { useLegacyPackaging = true } }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("io.github.junkfood02.youtubedl-android:library:0.17.2")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.17.2")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
