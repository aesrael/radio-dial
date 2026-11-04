plugins {
    id("com.android.application")
}

android {
    namespace = "home.custom.radiodial"
    compileSdk = 36

    defaultConfig {
        applicationId = "home.custom.radiodial"
        minSdk = 28
        targetSdk = 35
        versionCode = 7
        versionName = "1.6"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        if (rootProject.file("debug.keystore").exists()) {
            create("localDebug") {
                storeFile = rootProject.file("debug.keystore")
                storePassword = "tvhome"
                keyAlias = "tvkey"
                keyPassword = "tvhome"
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (rootProject.file("debug.keystore").exists()) signingConfig = signingConfigs.getByName("localDebug")
        }
        getByName("release") {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation("androidx.core:core:1.17.0")
    val media3 = "1.11.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-session:$media3")
}
