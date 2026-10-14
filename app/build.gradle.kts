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
        versionCode = 44
        versionName = "2.19.3"
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
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // Robolectric's ParcelFileDescriptor shadow (content:// tests) reads FileDescriptor.fd reflectively.
            all { it.jvmArgs("--add-opens=java.base/java.io=ALL-UNNAMED") }
        }
    }
    buildFeatures { viewBinding = true }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

val robolectricRuntime by configurations.creating

dependencies {
    val media3 = "1.3.1"
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-session:$media3")

    // Robolectric UI regression tests (JVM only; nothing here goes into the APK).
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core-ktx:1.5.0")
    robolectricRuntime("org.robolectric:android-all-instrumented:14-robolectric-10818077-i6")
}

// Robolectric's Android jar is resolved by Gradle (works through the proxy) and handed to the tests offline.
tasks.withType<Test>().configureEach {
    val rr = configurations.getByName("robolectricRuntime")
    inputs.files(rr)
    doFirst {
        systemProperty("robolectric.offline", "true")
        systemProperty("robolectric.dependency.dir", rr.singleFile.parentFile.absolutePath)
    }
    maxHeapSize = "2g"
    testLogging { events("passed", "failed"); showStandardStreams = true; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
