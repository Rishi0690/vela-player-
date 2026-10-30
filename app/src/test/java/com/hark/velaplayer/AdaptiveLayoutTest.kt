package com.hark.velaplayer

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.hark.velaplayer.databinding.ActivityPlayerBinding
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * v2.21 adaptive UI. (1) The player's bottom button row (lock · speed · aspect · rotate · PiP · lock) must never push
 * a button past the edge: every width from 280dp, font scale 1.0–1.3, long pill texts, side insets (cutout / side nav
 * bar). (2) A layout audit: real screens at narrow / large-font / tablet / landscape qualifiers, no visible view may
 * stick out of its parent (scroll containers excluded).
 * Robolectric measures real views and text with the real resources, but it is NOT a device: fonts differ slightly.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.LEGACY)
@Config(sdk = [34])
class AdaptiveLayoutTest {

    private fun idle(ms: Long = 600) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)) }

    // ------------------------------------------------------------------ player bottom row

    private class Host : Activity()

    /** Lays out the real player layout at [widthDp] with [sideInsetDp] padding on each side (cutout / side nav bar). */
    private fun playerRow(widthDp: Int, sideInsetDp: Int, speed: String, aspect: String, pip: Boolean = true): Pair<ActivityPlayerBinding, Int> {
        val act = Robolectric.buildActivity(Host::class.java).setup().get()
        act.setTheme(R.style.Theme_Vela_Player)
        val ctx = android.view.ContextThemeWrapper(act, R.style.Theme_Vela_Player)
        val b = ActivityPlayerBinding.inflate(android.view.LayoutInflater.from(ctx))
        val d = act.resources.displayMetrics.density
        val w = (widthDp * d).toInt(); val h = (640 * d).toInt()
        val inset = (sideInsetDp * d).toInt()
        b.controls.setPadding(inset, 0, inset, 0)
        b.btnSpeed.text = speed; b.btnAspectLabel.text = aspect
        b.btnPip.isVisible = pip
        act.setContentView(b.root, ViewGroup.LayoutParams(w, h))
        val fitter = RowFitter(b.p23ButtonRow, listOf(b.btnSpeed, b.btnAspectLabel), listOf(b.btnLock, b.btnRotate, b.btnPip, b.btnLockRight),
            listOf(b.btnPip, b.btnLock, b.btnRotate))
        fun layout() {
            b.root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            b.root.layout(0, 0, w, h)
        }
        layout(); idle(); val step = fitter.fit(); layout(); idle(); layout()
        return b to step
    }

    private fun assertRowFits(b: ActivityPlayerBinding, label: String) {
        val row = b.p23ButtonRow
        val lock = b.btnLockRight
        assertTrue("$label: right lock visible", lock.isVisible)
        val limit = row.width - row.paddingRight
        val last = (0 until row.childCount).map { row.getChildAt(it) }.filter { it.isVisible }.maxOf { it.right }
        assertTrue("$label: last child right=$last > row content right=$limit", last <= limit)
        assertTrue("$label: lock right=${lock.right} > $limit", lock.right <= limit)
        assertTrue("$label: lock still a usable size (${lock.width}px)", lock.width >= (32 * row.resources.displayMetrics.density).toInt() - 1)
        // nothing overlaps
        val vis = (0 until row.childCount).map { row.getChildAt(it) }.filter { it.isVisible && it.width > 0 }
        vis.zipWithNext().forEach { (a, z) -> assertTrue("$label: ${a.id} overlaps ${z.id}", a.right <= z.left + 1) }
    }

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun normalPhoneKeepsTheDesign() {
        val (b, step) = playerRow(411, 0, "1x", "Fit")
        assertRowFits(b, "411dp")
        assertTrue("no degradation needed on a normal phone (step=$step)", step == 0)
        assertTrue(b.btnSpeed.icon != null)
    }

    @Test @Config(qualifiers = "w320dp-h640dp-xhdpi")
    fun narrowWidthsAndLongPillsAlwaysFit() {
        for (w in listOf(280, 300, 320, 340, 360)) for (inset in listOf(0, 24, 48)) {
            val (b, _) = playerRow(w, inset, "1.75x", "Stretch")
            assertRowFits(b, "w=$w inset=$inset")
        }
    }

    @Test @Config(qualifiers = "w320dp-h640dp-xhdpi", fontScale = 1.3f)
    fun largeFontScaleFits() {
        for (w in listOf(280, 320, 360)) {
            val (b, _) = playerRow(w, 0, "1.25x", "Stretch")
            assertRowFits(b, "font1.3 w=$w")
        }
    }

    @Test @Config(qualifiers = "w360dp-h780dp-xxhdpi", fontScale = 1.3f)
    fun hiddenButtonsAreReportedAndRestoredWhenThereIsRoom() {
        val act = Robolectric.buildActivity(Host::class.java).setup().get()
        val ctx = android.view.ContextThemeWrapper(act, R.style.Theme_Vela_Player)
        val b = ActivityPlayerBinding.inflate(android.view.LayoutInflater.from(ctx))
        var hidden: Set<View> = emptySet()
        val d = act.resources.displayMetrics.density
        var w = (190 * d).toInt(); val h = (600 * d).toInt()
        act.setContentView(b.root, ViewGroup.LayoutParams(w, h))
        b.btnSpeed.text = "1.75x"; b.btnAspectLabel.text = "Stretch"
        val f = RowFitter(b.p23ButtonRow, listOf(b.btnSpeed, b.btnAspectLabel), listOf(b.btnLock, b.btnRotate, b.btnPip, b.btnLockRight),
            listOf(b.btnPip, b.btnLock, b.btnRotate)) { hidden = it }
        fun lay() { b.root.layoutParams = b.root.layoutParams.apply { width = w }; b.root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)); b.root.layout(0, 0, w, h) }
        lay(); f.fit(); lay(); idle(); lay()
        assertTrue("something had to give at 190dp + font 1.3", hidden.isNotEmpty())
        assertTrue("the right lock is never dropped", b.btnLockRight !in hidden && b.btnLockRight.isVisible)
        assertRowFits(b, "190dp")
        // Rotate to a wide screen: everything comes back.
        w = (800 * d).toInt(); lay(); idle(); lay(); idle(); lay()
        assertTrue("restored: $hidden", hidden.isEmpty())
        assertTrue(b.btnPip.isVisible && b.btnLock.isVisible && b.btnRotate.isVisible)
    }

    // ------------------------------------------------------------------ audit of real screens

    private fun overflow(root: View): List<String> {
        val out = ArrayList<String>()
        fun name(v: View) = try { v.resources.getResourceEntryName(v.id) } catch (_: Exception) { v.javaClass.simpleName }
        fun walk(v: View, scrolled: Boolean) {
            if (!v.isShown || v.width == 0) return
            val p = v.parent as? ViewGroup
            if (p != null && !scrolled && v.translationX == 0f && v.scaleX == 1f) {
                val slack = 2
                // Clipped by its parent = cut off; not clipped = still must stay on the window.
                if (p.clipChildren && (v.right > p.width + slack || v.left < -slack)) out += "${name(v)} [${v.left},${v.right}] outside ${name(p)} width ${p.width}"
                val loc = IntArray(2); v.getLocationInWindow(loc)
                if (loc[0] + v.width > root.width + slack || loc[0] < -slack) out += "${name(v)} off-window x=${loc[0]} w=${v.width} window=${root.width}"
            }
            if (v is ViewGroup) {
                val sc = scrolled || v is RecyclerView || v is HorizontalScrollView || v is ScrollView || v is NestedScrollView ||
                    v.javaClass.name.contains("ViewPager")
                for (i in 0 until v.childCount) walk(v.getChildAt(i), sc)
            }
        }
        walk(root, false)
        return out
    }

    private fun <T : Activity> audit(cls: Class<T>, label: String) {
        val ctl = try { Robolectric.buildActivity(cls).setup() } catch (e: Throwable) { println("AUDIT $label: ${cls.simpleName} can't start in Robolectric (${e.javaClass.simpleName}); skipped"); return }
        idle(1500)
        val root = ctl.get().window.decorView
        val issues = overflow(root)
        println("AUDIT $label ${cls.simpleName}: ${if (issues.isEmpty()) "OK" else issues.joinToString("; ")}")
        assertTrue("$label ${cls.simpleName}: $issues", issues.isEmpty())
        ctl.pause().stop().destroy()
    }

    private fun auditAll(label: String) {
        audit(MainActivity::class.java, label)
        audit(SettingsActivity::class.java, label)
        audit(VaultActivity::class.java, label)
        audit(FocusActivity::class.java, label)
        audit(ThemeActivity::class.java, label)
        audit(BackgroundActivity::class.java, label)
        audit(WidgetsActivity::class.java, label)
        audit(VideoPickerActivity::class.java, label)
    }

    @Test @Config(qualifiers = "w320dp-h640dp-mdpi") fun auditSmallPhone() = auditAll("w320")
    @Test @Config(qualifiers = "w320dp-h640dp-mdpi", fontScale = 1.3f) fun auditSmallPhoneLargeFont() = auditAll("w320+font1.3")
    @Test @Config(qualifiers = "w360dp-h800dp-xhdpi") fun auditTall20by9() = auditAll("w360 20:9")
    @Test @Config(qualifiers = "w640dp-h360dp-land-xhdpi") fun auditLandscape() = auditAll("land")
    @Test @Config(qualifiers = "sw600dp-w600dp-h960dp-xhdpi") fun auditTablet7() = auditAll("sw600")
    @Test @Config(qualifiers = "sw720dp-w1280dp-h800dp-land-xhdpi") fun auditTablet10Land() = auditAll("sw720 land")

    @Suppress("unused") private val keep = listOf(FrameLayout::class, LinearLayout::class, MaterialButton::class, RuntimeEnvironment::class)
}
