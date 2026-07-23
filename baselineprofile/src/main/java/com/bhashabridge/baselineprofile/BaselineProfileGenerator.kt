package com.bhashabridge.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 4 Baseline Profile generator. Records the classes and methods on BhashaBridge's startup and
 * first-translation path so ART AOT-compiles them at install instead of JIT-ing them on the first run.
 * It does not touch ONNX Runtime, the model cache, or the execution policy — a baseline profile only
 * changes *when* app bytecode is compiled, never what it computes.
 *
 * Journey: launch -> wait for the engine to finish loading (translate button enabled = tokenizer +
 * three .ort sessions built) -> type -> translate once -> read the output -> idle. Capturing the
 * translation compiles the tokenizer, decoder, and cache-plumbing methods too, not just Activity setup.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = PACKAGE) {
        pressHome()
        startActivityAndWait()

        // Engine loads asynchronously behind the UI; the translate button enables only once the
        // tokenizer and all three sessions are ready. Waiting here is what pulls engine + tokenizer +
        // model loading into the profile.
        val translate = By.res(PACKAGE, "translateButton")
        device.wait(Until.findObject(translate), READY_TIMEOUT_MS)
        waitUntilEnabled(translate)

        device.findObject(By.res(PACKAGE, "inputText"))?.text = PROBE
        device.findObject(translate)?.click()

        // One translation: wait for non-empty output, which means a full decode ran.
        device.wait(Until.findObject(By.res(PACKAGE, "outputText")), READY_TIMEOUT_MS)
        device.wait(Until.hasObject(By.res(PACKAGE, "outputText").textContains(" ")), TRANSLATE_TIMEOUT_MS)

        device.waitForIdle()
    }

    /** Polls until [selector] reports enabled — ProfileRule's scope exposes `device`. */
    private fun MacrobenchmarkScope.waitUntilEnabled(selector: BySelector) {
        val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (device.findObject(selector)?.isEnabled == true) return
            device.waitForIdle()
            Thread.sleep(200)
        }
    }

    private companion object {
        const val PACKAGE = "com.bhashabridge.app"
        const val PROBE = "Hello, how are you?"
        // Generous: the first journey iteration is a cold launch that bakes the three .ort graphs,
        // and an emulator is slower than the phone at that one-time step.
        const val READY_TIMEOUT_MS = 180_000L
        const val TRANSLATE_TIMEOUT_MS = 60_000L
    }
}
