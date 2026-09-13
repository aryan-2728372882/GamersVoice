import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics)
}

android {
    namespace = "com.gamervoice.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gamervoice.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0-beta"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            debugSymbolLevel = "none"
            abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a"))
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    val keystorePropertiesFile = rootProject.file("keystore.properties")
    val keystoreProperties = Properties()
    if (keystorePropertiesFile.exists()) {
        keystoreProperties.load(FileInputStream(keystorePropertiesFile))
    }

    signingConfigs {
        create("release") {
            val sFile = keystoreProperties.getProperty("storeFile")
            val sPass = keystoreProperties.getProperty("storePassword")
            val kAlias = keystoreProperties.getProperty("keyAlias")
            val kPass = keystoreProperties.getProperty("keyPassword")
            if (keystorePropertiesFile.exists() && !sFile.isNullOrBlank()) {
                storeFile = file(sFile.trim())
                storePassword = sPass?.trim()
                keyAlias = kAlias?.trim()
                keyPassword = kPass?.trim()
            } else {
                initWith(getByName("debug"))
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.webrtc)
    implementation(libs.okhttp)
    implementation(libs.play.services.auth)
    implementation(libs.play.services.ads)
    implementation(libs.user.messaging.platform)
    implementation(libs.firebase.messaging)
    implementation(libs.firebase.crashlytics)
    implementation("com.razorpay:checkout:1.6.41")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

configurations.all {
    resolutionStrategy {
        force("com.razorpay:standard-core:1.6.41")
    }
}

