plugins {
    id("com.android.application")
}

android {
    namespace = "com.coldcoffee.claudechan"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.coldcoffee.claudechan"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        // The WebP clips are already compressed; store them so the first
        // emote does not have to inflate a second time.
        noCompress += listOf("webp")
    }
}
