plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.pier.reelguard.igpatch"
    compileSdk = 35

    defaultConfig {
        // Embedded into Instagram's dex by the patch pipeline; the container
        // apk is never installed standalone.
        applicationId = "dev.pier.reelguard.igpatch"
        minSdk = 27
        targetSdk = 34
        versionCode = 6
        versionName = "0.6.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    // No runtime dependencies by design: hook-free, view-driven, reflection
    // only against classes living in the host.

    testImplementation(libs.junit)
    testImplementation(libs.json)
}
