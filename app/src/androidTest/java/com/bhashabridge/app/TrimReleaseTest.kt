package com.bhashabridge.app

import android.content.ComponentCallbacks2
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The release trigger, tested at the level where it actually broke.
 *
 * `onTrimMemory` was gated on `TRIM_MEMORY_COMPLETE`, which Android stopped delivering to apps
 * targeting API 34+ (ARCHITECTURE_RULES R4.6, LESSONS_FROM_V3 L14). The call site was present and
 * correct the whole time, so nothing that inspects code could have caught it — only exercising the
 * trigger at a level the platform still sends. That is what [trimAtBackgroundReleasesEngines] does,
 * and why it asserts on `BACKGROUND` specifically rather than "some trim level".
 *
 * Engine identity is the observable: `translator()` returns the same instance until a release, and a
 * different one after. That distinguishes "released" from "call ran but freed nothing" without
 * reading PSS, which is far too noisy for an assertion.
 *
 * Requires the model assets (~610 MB in `app/src/main/assets/`), because `translator()` builds real
 * ONNX sessions.
 */
@RunWith(AndroidJUnit4::class)
class TrimReleaseTest {

    private val app get() = ApplicationProvider.getApplicationContext<BhashaBridgeApp>()

    @Test
    fun trimBelowBackgroundKeepsEnginesResident() {
        val engine = app.translator(Direction.EN_TO_HI)

        app.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)

        assertSame(
            "UI_HIDDEN must not release: it fires on every home-press, and reloading costs ~27 s",
            engine,
            app.translator(Direction.EN_TO_HI),
        )
    }

    @Test
    fun trimAtBackgroundReleasesEngines() {
        val engine = app.translator(Direction.EN_TO_HI)

        app.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)

        assertNotSame(
            "BACKGROUND must release — this is the level API 34+ still delivers",
            engine,
            app.translator(Direction.EN_TO_HI),
        )
    }

    /**
     * One direction resident at a time.
     *
     * `getOrPut` kept every direction ever opened and only `onTrimMemory` ever evicted, so a single
     * tap on swap left both engines live — ~1.2 GB against the ~605 MB one direction costs, in the
     * foreground, where the low-memory killer is watching. Identity is again the observable: the
     * engine for the direction the user left must not survive a load of the other one.
     */
    @Test
    fun openingTheOtherDirectionEvictsTheFirst() {
        val enHi = app.translator(Direction.EN_TO_HI)

        app.translator(Direction.HI_TO_EN)

        assertNotSame(
            "the direction the user left must be released, not held alongside the new one",
            enHi,
            app.translator(Direction.EN_TO_HI),
        )
    }

    /** …but never underneath a live borrower: the eviction defers exactly like a trim does. */
    @Test
    fun evictionDuringABorrowIsDeferredUntilTheBorrowerFinishes() {
        val enHi = app.translator(Direction.EN_TO_HI)
        val borrowing = CountDownLatch(1)
        val release = CountDownLatch(1)

        val borrower = Thread {
            app.withResources {
                borrowing.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        }
        borrower.start()
        assertEquals(true, borrowing.await(10, TimeUnit.SECONDS))

        app.translator(Direction.HI_TO_EN)
        assertSame(
            "a borrower pins every engine, including the one being evicted",
            enHi,
            app.translator(Direction.EN_TO_HI),
        )

        release.countDown()
        borrower.join(10_000)
        // The deferred eviction keeps HI_TO_EN — the direction the last translator() call asked for.
        assertNotSame(
            "the deferred eviction must run once the last borrower finishes",
            enHi,
            app.translator(Direction.EN_TO_HI),
        )
    }

    /**
     * The half that makes the fix safe rather than fatal: a trim arriving mid-use must not close a
     * session out from under a live caller. Here the "caller" is a borrower held open on another
     * thread while the trim lands on this one.
     */
    @Test
    fun trimDuringABorrowIsDeferredUntilTheBorrowerFinishes() {
        val engine = app.translator(Direction.EN_TO_HI)
        val borrowing = CountDownLatch(1)
        val release = CountDownLatch(1)

        val borrower = Thread {
            app.withResources {
                borrowing.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        }
        borrower.start()
        assertEquals(true, borrowing.await(10, TimeUnit.SECONDS))

        app.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
        assertSame(
            "a trim during an active borrow must be deferred, not skipped and not executed",
            engine,
            app.translator(Direction.EN_TO_HI),
        )

        release.countDown()
        borrower.join(10_000)

        assertNotSame(
            "the deferred release must run once the last borrower finishes",
            engine,
            app.translator(Direction.EN_TO_HI),
        )
    }
}
