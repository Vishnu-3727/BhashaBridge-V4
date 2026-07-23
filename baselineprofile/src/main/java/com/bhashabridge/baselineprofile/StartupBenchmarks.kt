package com.bhashabridge.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 4 startup measurement. The same journey timed two ways so the Baseline Profile's effect is
 * isolated: [CompilationMode.None] JITs everything (profile OFF), [CompilationMode.Partial] with
 * [BaselineProfileMode.Require] AOT-compiles exactly the generated profile (profile ON) and fails if
 * the profile is missing, so a green ON run is itself proof the profile was packaged and installed.
 *
 * Cold and warm are both measured. Does not touch inference — ORT session creation is native and
 * unaffected by ART compilation; what moves is the class-loading / UI / tokenizer / decode glue.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmarks {

    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test fun coldNoProfile() = startup(StartupMode.COLD, CompilationMode.None())
    @Test fun coldBaselineProfile() = startup(StartupMode.COLD, CompilationMode.Partial(BaselineProfileMode.Require))
    @Test fun warmNoProfile() = startup(StartupMode.WARM, CompilationMode.None())
    @Test fun warmBaselineProfile() = startup(StartupMode.WARM, CompilationMode.Partial(BaselineProfileMode.Require))

    private fun startup(mode: StartupMode, compilation: CompilationMode) = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        iterations = 10,
        startupMode = mode,
        compilationMode = compilation,
    ) {
        pressHome()
        startActivityAndWait()
        // Let the async engine load settle so the run also exercises the tokenizer/model-load glue.
        device.wait(Until.hasObject(By.res(PACKAGE, "translateButton")), 30_000)
        device.waitForIdle()
    }

    private companion object {
        const val PACKAGE = "com.bhashabridge.app"
    }
}
