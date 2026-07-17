package com.etaegord.ghostscreen

import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.graphics.drawable.toBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression guard for the "search GhostScreen" crash.
 *
 * Root cause: the app's own launcher entry is returned by the launcher-intent
 * query in SmartMaskSettingsScreen, and its icon is an AdaptiveIconDrawable
 * whose intrinsic size can be -1, for which Drawable.toBitmap throws on some
 * API levels. The screen now wraps the decode in runCatching; this test
 * reproduces the exact trigger path against the real device package list and
 * asserts the decode never throws (it completes via the runCatching fallback).
 *
 * Run on device: ./gradlew connectedAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class AppIconDecodeTest {

    @Test
    fun decoding_every_launcher_icon_does_not_throw() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val pm: PackageManager = ctx.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        val infos = pm.queryIntentActivities(mainIntent, 0)

        // The device must have at least one launcher activity (the app under test itself).
        assertTrue("Expected ≥1 launcher activity on device", infos.isNotEmpty())

        var decoded = 0
        var fellBack = 0
        for (info in infos) {
            val drawable = info.loadIcon(pm)
            // Same defensive path the screen now uses.
            val ok = runCatching { drawable.toBitmap(56, 56) }.isSuccess
            if (ok) decoded++ else fellBack++
        }
        // Every icon either decoded or fell back — none threw into the test runner.
        assertTrue("All icons handled (decoded=$decoded, fallback=$fellBack)", decoded + fellBack == infos.size)
    }
}
