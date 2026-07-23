// Top-level build file where you can add configuration options common to all sub-projects/modules.
// Every AGP/androidx plugin is declared here `apply false` so the Android Gradle classpath is loaded
// once at the root; modules then apply their aliases without re-resolving the version (required once
// :baselineprofile pulls in com.android.test alongside :app's com.android.application).
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.androidx.baselineprofile) apply false
}