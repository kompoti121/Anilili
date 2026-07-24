import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    // Kotlin/Native can only link Apple binaries on a macOS host. Declaring the iOS targets
    // unconditionally would break Gradle sync on the Windows dev machines, so they only
    // exist where they can actually compile. CI/release iOS builds must run on macOS.
    if (DefaultNativePlatform.getCurrentOperatingSystem().isMacOsX) {
        iosX64()
        iosArm64()
        iosSimulatorArm64()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.okio)
        }
        androidMain.dependencies {
            implementation(libs.okhttp)
        }
    }
}

android {
    namespace = "com.miruronative.shared"
    compileSdk = 36
    // The SDK's default 35.0.0 install is corrupted on the dev machines; pin the same
    // known-good build tools the app module uses.
    buildToolsVersion = "35.0.1"

    defaultConfig {
        // Matches the app's Fire OS 5 floor.
        minSdk = 22
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
