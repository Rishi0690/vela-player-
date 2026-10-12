plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hark.velaplayer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.hark.velaplayer"
        minSdk = 24
        targetSdk = 34
        versionCode = 7
        versionName = "2.0"
    }
    // One fixed keystore for every release so new versions install over old ones and keep settings.
    // It is the same key that signed v1.1. Never replace it.
    signingConfigs {
        create("vela") {
            storeFile = rootProject.file("signing/vela-release.jks")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        // Only sign with the release key when it is present (it is gitignored). Without it, builds
        // still compile but fall back to the default debug key, which cannot update an installed release.
        val velaKey = rootProject.file("signing/vela-release.jks").exists()
        release {
            isMinifyEnabled = false
            if (velaKey) signingConfig = signingConfigs.getByName("vela")
        }
        debug {
            if (velaKey) signingConfig = signingConfigs.getByName("vela")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    val media3 = "1.3.1"
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-session:$media3")
}
