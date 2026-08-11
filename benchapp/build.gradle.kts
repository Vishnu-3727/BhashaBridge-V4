plugins {
    // AGP 9 registers the kotlin extension itself; no standalone kotlin plugin (same as :app).
    alias(libs.plugins.android.application)
}

/**
 * :app's inference sources, mirrored into the build directory so this module can compile them.
 *
 * A `Sync` rather than a second `srcDir` on `../app/src/main/java`: AGP 9 replaced the filterable
 * `srcDir` with a plain `directories` set that has no include/exclude, and this module must take
 * `mt/`, `bench/` and `speech/` while leaving `ui/` and `BhashaBridgeApp.kt` behind — those need
 * layouts and string resources, which do not belong in a benchmark.
 *
 * `speech/` was excluded here originally, on the reasoning that it "needs Vosk". It does, and that
 * turned out to be the only thing it needs: not one file under `speech/` references `R`, a layout or
 * an Activity. Excluding it meant this app could characterise a phone's CPU, its thermal envelope and
 * its translation latency while saying nothing at all about the recogniser — on a device where the
 * recogniser is half the pipeline.
 *
 * It is a mirror, never a fork: the task re-runs whenever :app's sources change, and `Sync` deletes
 * anything no longer present upstream, so these files cannot silently drift from the code the app
 * ships. Edit them in `app/src/main/java`; edits here are overwritten on the next build.
 */
// Created at configuration time on purpose: AGP resolves `sourceSets.java.directories` while
// configuring, and drops entries that do not exist yet — the Sync task has not run at that point,
// so a first clean build would silently compile without any of these sources.
val sharedInferenceSources: java.io.File =
    layout.buildDirectory.dir("shared-inference-src").get().asFile.apply { mkdirs() }

val syncInferenceSources by tasks.registering(Sync::class) {
    description = "Mirrors :app's mt/ and bench/ sources into :benchapp so both measure the same runtime."
    from(rootProject.layout.projectDirectory.dir("app/src/main/java")) {
        exclude(
            "com/bhashabridge/app/ui/**",
            "com/bhashabridge/app/BhashaBridgeApp.kt",
        )
    }
    into(sharedInferenceSources)
}

// Every Kotlin/Java compilation in this module reads the mirror, so it has to exist first.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(syncInferenceSources)
}
tasks.withType<JavaCompile>().configureEach {
    dependsOn(syncInferenceSources)
}

android {
    // Deliberately the SAME namespace as :app. The namespace fixes the package of the generated
    // BuildConfig, and this module compiles :app's own `mt/` and `bench/` sources (see sourceSets
    // below) which import `com.bhashabridge.app.BuildConfig`. Sharing the namespace is what lets
    // those files compile here byte-for-byte unmodified instead of being forked.
    //
    // The installed identity is `applicationId`, not `namespace`, so this still installs alongside
    // the real app rather than replacing it.
    namespace = "com.bhashabridge.app"

    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.bhashabridge.bench"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // Metrics and logDebug compile themselves out via BuildConfig.DEBUG, so this is load-bearing
    // here for exactly the reason it is in :app.
    buildFeatures {
        buildConfig = true
    }

    /**
     * The measurement code under test is :app's, not a copy of it.
     *
     * A benchmark that reimplements the thing it measures reports on the reimplementation. The
     * whole value of this module is that `MtEngine`, `OnnxModels`, `Tokenizer`, `GreedyDecoder`,
     * `CpuCapabilities`, `ExecutionPolicy`, `SystemStats` and `Stats` are the *same files* the app
     * ships — a change to the KV-cache runtime shows up in these numbers automatically, and cannot
     * silently drift from what the app does.
     *
     * `ui/` is excluded: it needs layouts and string resources, which this module has no reason to
     * carry. `BhashaBridgeApp.kt` is excluded because it is the app's process-scoped resource owner
     * and this module owns its resources per phase. What is left — `mt/`, `bench/`, `speech/`,
     * `Direction`, `Logging` — depends only on ONNX Runtime, Vosk and the platform, which is exactly
     * the pipeline this app exists to measure.
     */
    sourceSets.getByName("main") {
        // Both sets: AGP 9 keeps `java` and `kotlin` as separate source directories, and the
        // mirrored files are Kotlin. Registering only `java` compiles nothing and fails with
        // "unresolved reference" on every shared type, which is a confusing way to learn this.
        java.directories.add(sharedInferenceSources.absolutePath)
        kotlin.directories.add(sharedInferenceSources.absolutePath)
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    packaging {
        jniLibs {
            // Same list as :app — ORT and Vosk each ship their own copy of some of these.
            pickFirsts += setOf(
                "**/libonnxruntime.so",
                "**/libonnxruntime4j_jni.so",
                "**/libvosk.so",
            )
        }
    }

    // The MT phase needs arm64 ORT. No universal APK, for the same reason :app has none.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }
}

dependencies {
    // ORT and Vosk runtimes, but no *models* in the APK — both the ONNX graphs and the Vosk acoustic
    // model are sideloaded at runtime, which is what keeps this a few MB and installable on any test
    // device. The audio fixture is the one exception: 85 KB, and a speech benchmark that needed the
    // operator to supply their own audio would not be comparable between phones.
    implementation(libs.onnxruntime.android)
    implementation(libs.vosk.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.material)

    testImplementation(libs.junit)
}
