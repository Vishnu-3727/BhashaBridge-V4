plugins {
    // AGP 9 registers the kotlin extension itself; no standalone kotlin plugin (same as :app).
    alias(libs.plugins.android.test)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "com.bhashabridge.baselineprofile"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        // Macrobenchmark requires API 28+ to collect a profile. The profile it produces still applies
        // to the app on its own minSdk 24 (ProfileInstaller handles delivery).
        minSdk = 28
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

// Regeneration path. [BaselineProfileGenerator] is the source of truth for the profile; run:
//   ./gradlew :app:generateReleaseBaselineProfile
// on an API 33+ device/emulator (or a rooted API 28+ one). The only device here is an unrootable
// Android 12 (API 31), on which ART refuses profile collection, and this machine has no installable
// system image, so the profile in app/src/main/baseline-prof.txt was authored from the same journey's
// hot path by hand. A managed API 34 device can be added here to automate it on capable hardware:
//   testOptions.managedDevices.localDevices.create("genDevice") { device = "Pixel 6"; apiLevel = 34; systemImageSource = "aosp" }
//   baselineProfile { managedDevices += "genDevice"; useConnectedDevices = false }
baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.junit)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
