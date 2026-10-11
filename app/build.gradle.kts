plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "fr.masalle.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "fr.masalle.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// No runtime dependency on purpose: everything the app does (HTTP, AES, RSA,
// deflate, JSON, widgets) is in the Android platform. Nothing third party
// ever sees a student's token or timetable.
dependencies {
    testImplementation("junit:junit:4.13.2")
    // android.jar only ships stubs of org.json, the JVM tests need the real one.
    testImplementation("org.json:json:20250517")
}
