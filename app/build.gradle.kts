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

// PostHog's project key (write only), set by hand in local.properties, never committed.
// Without it the build sends no statistics and never asks.
val local = Properties().apply {
    val file = rootProject.file("local.properties")
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
        versionCode = 17
        versionName = "0.7.6"
        buildConfigField("String", "POSTHOG_KEY", "\"${local.getProperty("posthog.key", "").trim()}\"")
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
        // Debug installs next to the real app, so testing never logs the student out.
        debug {
            applicationIdSuffix = ".debug"
            resValue("string", "app_name", "Sallio test")
        }
        // Sallio test, shrunk like the release: to see what R8 changes, with the debug tools.
        create("shrunk") {
            initWith(getByName("debug"))
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            matchingFallbacks += listOf("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            resValue("string", "app_name", "Sallio")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (signing.containsKey("storeFile")) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        buildConfig = true
        resValues = true
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
