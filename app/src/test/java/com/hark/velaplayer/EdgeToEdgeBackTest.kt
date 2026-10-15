package com.hark.velaplayer

import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.PreferenceFragmentCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * v2.20 target SDK 36: edge-to-edge insets (EdgeToEdge) and predictive-back-safe Back handling
 * (OnBackPressedDispatcher only; Android 16 no longer calls onBackPressed / delivers KEYCODE_BACK).
 * Runs on the SDK 34 Robolectric runtime (no SDK 36 android-all offline), which exercises the same AndroidX code paths.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.LEGACY)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi")
class EdgeToEdgeBackTest {

    private fun idle(ms: Long = 1500) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)) }

    private fun insets(top: Int, bottom: Int, ime: Int = 0, left: Int = 0, right: Int = 0, cutoutTop: Int = 0): WindowInsetsCompat {
        val b = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, top, 0, 0))
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(left, 0, right, bottom))
            .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(0, cutoutTop, 0, 0))
        if (ime > 0) b.setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, ime)).setVisible(WindowInsetsCompat.Type.ime(), true)
        return b.build()
    }

    // ------------------------------------------------------------------ EdgeToEdge helper

    @Test
    fun padAddsInsetsOnceAndKeepsOwnPadding() {
        val c = ApplicationProvider.getApplicationContext<android.content.Context>()
        val v = TextView(c).apply { setPadding(5, 6, 7, 8) }
        EdgeToEdge.pad(v, left = true, top = true, right = true)
        ViewCompat.dispatchApplyWindowInsets(v, insets(top = 60, bottom = 40, left = 10, right = 12))
        assertEquals(listOf(15, 66, 19, 8), listOf(v.paddingLeft, v.paddingTop, v.paddingRight, v.paddingBottom))
        // A second pass (e.g. rotation, bars changing) never accumulates.
        ViewCompat.dispatchApplyWindowInsets(v, insets(top = 60, bottom = 40, left = 10, right = 12))
        assertEquals(66, v.paddingTop)
        // Bars change: only the difference is applied, and padding set by other code on other sides survives.
        v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, 30)
        ViewCompat.dispatchApplyWindowInsets(v, insets(top = 24, bottom = 40))
        assertEquals(listOf(5, 30, 7, 30), listOf(v.paddingLeft, v.paddingTop, v.paddingRight, v.paddingBottom))
    }

    @Test
    fun cutoutCountsAsSafeArea() {
        val c = ApplicationProvider.getApplicationContext<android.content.Context>()
        val v = FrameLayout(c)
        EdgeToEdge.root(v)
        ViewCompat.dispatchApplyWindowInsets(v, insets(top = 50, bottom = 0, cutoutTop = 90))
        assertEquals(90, v.paddingTop)
    }

    @Test
    fun listGetsNavBarOrKeyboardAndDrawsUnderIt() {
        val c = ApplicationProvider.getApplicationContext<android.content.Context>()
        val list = androidx.recyclerview.widget.RecyclerView(c).apply { setPadding(0, 0, 0, 88) }
        EdgeToEdge.list(list, ime = true)
        assertFalse(list.clipToPadding)
        ViewCompat.dispatchApplyWindowInsets(list, insets(top = 60, bottom = 48))
        assertEquals(88 + 48, list.paddingBottom)
        ViewCompat.dispatchApplyWindowInsets(list, insets(top = 60, bottom = 48, ime = 700))
        assertEquals(88 + 700, list.paddingBottom) // keyboard open: last rows can scroll above it (old adjustResize)
        ViewCompat.dispatchApplyWindowInsets(list, insets(top = 60, bottom = 48))
        assertEquals(88 + 48, list.paddingBottom)
        assertEquals(0, list.paddingTop)
    }

    @Test
    fun marginMovesFabAboveNavBar() {
        val c = ApplicationProvider.getApplicationContext<android.content.Context>()
        val parent = FrameLayout(c)
        val fab = View(c)
        parent.addView(fab, FrameLayout.LayoutParams(10, 10).apply { bottomMargin = 16; rightMargin = 16 })
        EdgeToEdge.margin(fab)
        ViewCompat.dispatchApplyWindowInsets(parent, insets(top = 60, bottom = 48, right = 30))
        val lp = fab.layoutParams as FrameLayout.LayoutParams
        assertEquals(64, lp.bottomMargin)
        assertEquals(16, lp.rightMargin) // parent not padded here, but margin() defaults to bottom only
        ViewCompat.dispatchApplyWindowInsets(parent, insets(top = 60, bottom = 20))
        assertEquals(36, (fab.layoutParams as FrameLayout.LayoutParams).bottomMargin)
    }

    // ------------------------------------------------------------------ real screens

    @Test
    fun settingsIsEdgeToEdgeAndPadsToolbarAndLists() {
        val a = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        idle()
        val root = a.findViewById<View>(R.id.toolbar).parent as View
        ViewCompat.dispatchApplyWindowInsets(a.window.decorView, insets(top = 63, bottom = 42))
        idle(100)
        assertEquals("toolbar sits below the status bar", 63, root.paddingTop)
        val f = a.supportFragmentManager.fragments.first { it is SettingsActivity.RootFragment } as PreferenceFragmentCompat
        val d = a.resources.displayMetrics.density
        assertEquals("settings list clears the nav bar", (24 * d).toInt() + 42, f.listView.paddingBottom)
        assertFalse(f.listView.clipToPadding)
        // A category screen gets the same treatment.
        a.open("appearance"); idle()
        val sec = a.supportFragmentManager.fragments.last { it is SettingsActivity.SectionFragment } as PreferenceFragmentCompat
        ViewCompat.dispatchApplyWindowInsets(a.window.decorView, insets(top = 63, bottom = 42))
        idle(100)
        assertEquals((24 * d).toInt() + 42, sec.listView.paddingBottom)
    }

    @Test
    fun backgroundScreenRootClearsStatusBar() {
        val a = Robolectric.buildActivity(BackgroundActivity::class.java).setup().get()
        idle()
        val root = (a.findViewById<android.view.ViewGroup>(android.R.id.content)).getChildAt(0)
        ViewCompat.dispatchApplyWindowInsets(a.window.decorView, insets(top = 70, bottom = 30))
        idle(100)
        assertEquals(70, root.paddingTop)
    }

    @Test
    fun settingsBackOrderIsSearchThenCategoryThenClose() {
        val a = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        idle()
        // Plain main screen: no callback is enabled, so Android 16 can play the predictive back-to-home animation.
        assertFalse(a.onBackPressedDispatcher.hasEnabledCallbacks())
        a.open("appearance"); idle()
        a.findViewById<TextView>(R.id.search).text = "dim"
        idle(300)
        assertTrue(a.onBackPressedDispatcher.hasEnabledCallbacks())

        a.onBackPressedDispatcher.onBackPressed(); idle()
        assertEquals("1st Back clears the search", "", a.findViewById<TextView>(R.id.search).text.toString())
        assertEquals(1, a.supportFragmentManager.backStackEntryCount)
        assertFalse(a.isFinishing)

        a.onBackPressedDispatcher.onBackPressed(); idle()
        assertEquals("2nd Back closes the category", 0, a.supportFragmentManager.backStackEntryCount)
        assertFalse(a.isFinishing)
        assertFalse(a.onBackPressedDispatcher.hasEnabledCallbacks())

        a.onBackPressedDispatcher.onBackPressed(); idle()
        assertTrue("3rd Back closes Settings", a.isFinishing)
    }
}
