import java.util.Properties

plugins {
    id("com.android.application")
}

// Signed with the same private key as the custom Aerial Views build
val signingProps =
    Properties().apply {
        val file = file("${System.getProperty("user.home")}/tcl-tv/build/signing/release.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }

android {
    namespace = "local.tvhome"
    compileSdk = 37

    defaultConfig {
        applicationId = "local.tvhome"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("release") {
            storeFile = signingProps.getProperty("storeFile")?.let { file(it) }
            storePassword = signingProps.getProperty("storePassword")
            keyAlias = signingProps.getProperty("keyAlias")
            keyPassword = signingProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
