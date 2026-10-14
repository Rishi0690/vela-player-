package com.hark.velaplayer

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.preference.PreferenceFragmentCompat
import androidx.recyclerview.widget.RecyclerView
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.LEGACY)
@Config(sdk = [34], qualifiers = "w360dp-h520dp-xhdpi")
class SettingsScrollTest {

    private fun idle(ms: Long = 1500) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)) }

    private fun rootList(a: SettingsActivity): RecyclerView {
        val f = a.supportFragmentManager.fragments.first { it is SettingsActivity.RootFragment } as PreferenceFragmentCompat
        return f.listView
    }

    private fun dump(tag: String, a: SettingsActivity) {
        val l = rootList(a)
        val c = a.findViewById<ViewGroup>(R.id.container)
        var v: View? = l; val chain = StringBuilder()
        while (v != null) { chain.append("${v.javaClass.simpleName}#${try { v.resources.getResourceEntryName(v.id) } catch (e: Exception) { "-" }}(h=${v.height},mh=${v.measuredHeight},top=${v.top},pad=${v.paddingTop}/${v.paddingBottom},sy=${v.scrollY}) < "); v = v.parent as? View }
        if (tag == "start") println("[$tag] chain=$chain items=${l.adapter?.itemCount} childCount=${l.childCount} lastBottom=${l.getChildAt(l.childCount-1)?.bottom}")
        println("[$tag] shown=${l.isShown} h=${l.height} pad=${l.paddingTop}/${l.paddingBottom} off=${l.computeVerticalScrollOffset()} " +
            "range=${l.computeVerticalScrollRange()} ext=${l.computeVerticalScrollExtent()} up=${l.canScrollVertically(-1)} down=${l.canScrollVertically(1)} " +
            "suppressed=${l.isLayoutSuppressed} state=${l.scrollState} children=${(0 until c.childCount).map { val v = c.getChildAt(it); "${v.javaClass.simpleName}(vis=${v.visibility},a=${v.alpha},z=${v.translationZ},ty=${v.translationY},anim=${v.animation != null})" }} " +
            "frags=${a.supportFragmentManager.fragments.map { "${it.javaClass.simpleName}:hidden=${it.isHidden}" }} back=${a.supportFragmentManager.backStackEntryCount}")
    }

    /** Swipes on the screen centre through the real touch pipeline; returns how far the root list moved. */
    private fun swipe(a: SettingsActivity, dy: Int): Int {
        val l = rootList(a)
        val before = l.computeVerticalScrollOffset()
        val decor = a.window.decorView
        val loc = IntArray(2); l.getLocationInWindow(loc)
        val x = loc[0] + l.width / 2f; val y0 = loc[1] + l.height / 2f
        val t = SystemClock.uptimeMillis()
        decor.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y0, 0))
        for (i in 1..10) decor.dispatchTouchEvent(MotionEvent.obtain(t, t + i * 16L, MotionEvent.ACTION_MOVE, x, y0 + dy * i / 10f, 0))
        decor.dispatchTouchEvent(MotionEvent.obtain(t, t + 176, MotionEvent.ACTION_UP, x, y0 + dy, 0))
        l.stopScroll()
        idle(50)
        return l.computeVerticalScrollOffset() - before
    }

    private fun roundTrip(sources: List<String>, startAtBottom: Boolean) {
        val ctl = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val a = ctl.get()
        idle()
        val l = rootList(a)
        if (startAtBottom) { l.scrollToPosition(l.adapter!!.itemCount - 1); idle() }
        dump("start", a)
        for ((i, src) in sources.withIndex()) {
            a.open("appearance"); idle()
            // Tap "Background" -> BackgroundActivity covers Settings.
            ctl.pause().stop()
            val bg = Robolectric.buildActivity(BackgroundActivity::class.java).setup()
            idle()
            when (src) {
                "restore" -> Backdrop.restoreDefaults(bg.get())
                "glass" -> Backdrop.update(bg.get(), style = Backdrop.STYLE_GLASS, blur = 20 + i)
                "clear" -> Backdrop.update(bg.get(), style = Backdrop.STYLE_CLEAR, dim = 0)
                else -> Backdrop.update(bg.get(), source = src)
            }
            idle()
            // Back #1: Background closes, Settings (Appearance) returns.
            bg.pause().stop().destroy()
            ctl.restart().start().resume()
            idle()
            dump("back1-$i", a)
            // Back #2: Appearance -> main Settings.
            a.onBackPressedDispatcher.onBackPressed()
            idle()
            dump("back2-$i", a)
            val upBefore = rootList(a).canScrollVertically(-1)
            val up = swipe(a, 900)     // finger down = scroll up
            val down = swipe(a, -900)  // finger up = scroll down
            dump("after-swipes-$i", a)
            println("[$i:$src] moved up=$up down=$down")
            val r = rootList(a)
            assertTrue("main Settings list must be shown", r.isShown)
            assertTrue("list must not be layout-suppressed", !r.isLayoutSuppressed)
            assertTrue("top padding", r.paddingTop == a.searchBoxHeight())
            // Nothing but the main screen may sit in the container once Back finished.
            val c = a.findViewById<ViewGroup>(R.id.container)
            assertTrue("stale screens on top", (0 until c.childCount).count { c.getChildAt(it).visibility == View.VISIBLE } == 1)
            assertTrue("must scroll up by touch (moved $up)", up < 0 || upBefore == false)
            assertTrue("must scroll down by touch (moved $down)", down > 0 || !r.canScrollVertically(1))
            // Reach both ends.
            r.scrollToPosition(0); idle(); assertTrue(!r.canScrollVertically(-1))
            r.scrollToPosition(r.adapter!!.itemCount - 1); idle(); assertTrue(!r.canScrollVertically(1))
            if (i % 2 == 0) { r.scrollToPosition(0); idle() }
        }
    }

    @Test fun returnFromBackground_builtins_fromBottom() {
        val ids = Backdrop.BUILTINS.take(3).map { "b:" + it.id }
        roundTrip(ids.take(1) + listOf("clear"), startAtBottom = true)
    }

    /** Activity recreated / process restored while Background (or the photo picker) was on top. */
    @Test fun returnAfterRecreate() {
        var ctl = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        idle()
        for (i in 0 until 3) {
            val a0 = ctl.get()
            if (i == 0) { rootList(a0).scrollToPosition(rootList(a0).adapter!!.itemCount - 1); idle() }
            if (a0.supportFragmentManager.backStackEntryCount == 0) { a0.open("appearance"); idle() }
            ctl.pause().stop()
            Backdrop.update(a0, source = "b:" + Backdrop.BUILTINS[i].id)
            val state = android.os.Bundle()
            ctl.saveInstanceState(state).destroy()
            ctl = Robolectric.buildActivity(SettingsActivity::class.java).setup(state)
            idle()
            val a = ctl.get()
            dump("recreated-$i", a)
            a.onBackPressedDispatcher.onBackPressed(); idle()
            dump("rc-back-$i", a)
            val r = rootList(a)
            r.scrollToPosition(r.adapter!!.itemCount - 1); idle()
            val up = swipe(a, 900); val down = swipe(a, -900)
            dump("rc-swipes-$i", a)
            println("[rc $i] up=$up down=$down")
            assertTrue("top padding ${r.paddingTop} vs search ${a.searchBoxHeight()}", a.searchBoxHeight() == 0 || r.paddingTop == a.searchBoxHeight())
            assertTrue(r.isShown); assertTrue("up $up", up < 0); assertTrue("down $down", down > 0)
        }
    }

    @Test fun returnFromBackground_fromTop() {
        roundTrip(listOf("b:" + Backdrop.BUILTINS.last().id, "clear", "glass"), startAtBottom = false)
    }
}
