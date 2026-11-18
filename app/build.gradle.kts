plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mg4.netdiag"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mg4.netdiag"
        minSdk = 28
        targetSdk = 28
        versionCode = 2
        versionName = "0.2.0"
    }

    lint {
        // Head unit is Android 9 (API 28) and the app isn't on Google Play.
        disable += "ExpiredTargetSdkVersion"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
