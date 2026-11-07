import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// CI knobs (all optional):  -Pshare64.abis=arm64-v8a  -Pshare64.universal=false
//                           -Pshare64.versionName=1.2.0  -Pshare64.versionCode=120
val abis: List<String> = (findProperty("share64.abis") as String?)
    ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
    ?: listOf("armeabi-v7a", "arm64-v8a", "x86_64")
val universal: Boolean = (findProperty("share64.universal") as String?)?.toBoolean() ?: true

// Release signing comes from the environment (CI secrets). Without it, release builds are signed
// with the debug key so they stay installable for testing.
val keystorePath: String? = System.getenv("SHARE64_KEYSTORE_FILE")

android {
    namespace = "com.share64.app.mobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.share64.app"
        minSdk = 29
        targetSdk = 36
        versionCode = (findProperty("share64.versionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (findProperty("share64.versionName") as String?) ?: "1.0.0"
    }

    signingConfigs {
        if (keystorePath != null && file(keystorePath).exists()) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("SHARE64_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SHARE64_KEY_ALIAS")
                keyPassword = System.getenv("SHARE64_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include(*abis.toTypedArray())
            isUniversalApk = universal
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core-app"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
}
