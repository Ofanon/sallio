import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The release key lives only on this PC (see README). Without it the release
// build is left unsigned instead of failing, so the project still builds elsewhere.
val signing = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "fr.sallio.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "fr.sallio.app"
        minSdk = 26
        targetSdk = 36
        // Bump both for every GitHub release: the in-app updater compares versionCode.
        versionCode = 2
        versionName = "0.2"
    }

    signingConfigs {
        if (signing.containsKey("storeFile")) {
            create("release") {
                storeFile = rootProject.file(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
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
            if (signing.containsKey("storeFile")) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        buildConfig = true
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
