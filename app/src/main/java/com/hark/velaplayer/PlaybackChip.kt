package com.hark.velaplayer

import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible

/**
 * v2.3: the single compact, translucent playback message chip (same look as the v2.2 Shuffle/Loop/Auto-play chip).
 *
 * Every temporary player message goes through [show]: brightness, volume/boost (with a thin level bar), speed
 * (incl. press-and-hold speed), seek/scrub feedback, zoom, playback modes and short notices/errors. Where it sits
 * is decided by the activity ([placement]): bottom-centre below the timeline, above the system navigation area.
 * Only one message is visible at a time; a new one replaces the text in place.
 */
class PlaybackChip(
    private val chip: View,
    private val icon: ImageView,
    private val text: TextView,
    private val track: View,
    private val fill: View,
    /** Returns the target (translationX, translationY, scale) for a chip of the given measured size. */
    private val placement: (w: Int, h: Int) -> Placement,
) {
    data class Placement(val tx: Float, val ty: Float, val scale: Float)

    companion object {
        const val SHORT = 1200L
        const val LONG = 2600L
        private const val WHITE = 0xFFFFFFFF.toInt()
    }

    private val handler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hide() }
    private var hiding = false
    private var lastText: CharSequence? = null
    private val density = chip.resources.displayMetrics.density

    val isShowing: Boolean get() = chip.isVisible && !hiding

    init { fill.pivotX = 0f }

    /**
     * Shows [msg] with [iconRes] (0 = no icon). [level] 0..100 shows the thin level bar; [accent] (non-null) tints the
     * icon, text and bar (used for the volume boost range). [sticky] keeps it up until [hideSoon]/[hide] is called.
     */
    fun show(iconRes: Int, msg: CharSequence, level: Int? = null, accent: Int? = null,
             durationMs: Long = SHORT, sticky: Boolean = false) {
        handler.removeCallbacks(hideRunnable)
        if (iconRes != 0) icon.setImageResource(iconRes)
        icon.isVisible = iconRes != 0
        text.text = msg
        val tint = accent ?: WHITE
        icon.imageTintList = ColorStateList.valueOf(tint)
        text.setTextColor(tint)
        if (level != null) {
            track.isVisible = true
            track.backgroundTintList = if (accent != null) ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent, 0x55)) else null
            fill.backgroundTintList = if (accent != null) ColorStateList.valueOf(ColorUtils.blendARGB(accent, 0xFFFFC46B.toInt(), 0.45f)) else null
            fill.scaleX = level.coerceIn(0, 100) / 100f
        } else track.isVisible = false
        chip.contentDescription = msg
        val wasShown = chip.isVisible && !hiding && chip.alpha > 0.05f
        hiding = false
        chip.isVisible = true
        chip.post { place(animateIn = !wasShown) }
        // Levels change many times a second while swiping: announce only real messages.
        if (level == null && msg != lastText) chip.announceForAccessibility(msg)
        lastText = msg
        if (!sticky) handler.postDelayed(hideRunnable, durationMs)
    }

    /** Re-applies the position (controls shown/hidden, rotation) while the chip is up. */
    fun reposition() { if (isShowing) chip.post { place(animateIn = false) } }

    fun hideSoon(delayMs: Long = 700L) {
        handler.removeCallbacks(hideRunnable)
        if (chip.isVisible) handler.postDelayed(hideRunnable, delayMs)
    }

    fun hide() {
        handler.removeCallbacks(hideRunnable)
        if (!chip.isVisible || hiding) return
        hiding = true
        lastText = null
        chip.animate().cancel()
        chip.animate().alpha(0f).translationY(chip.translationY + 6 * density).setDuration(220)
            .setInterpolator(Motion.emphasizedAccel).withEndAction { if (hiding) { chip.isVisible = false; hiding = false } }.start()
    }

    /** Immediately gone (PiP, leaving the screen). */
    fun hideNow() {
        handler.removeCallbacks(hideRunnable)
        chip.animate().cancel()
        chip.isVisible = false
        hiding = false
        lastText = null
    }

    fun release() { handler.removeCallbacksAndMessages(null) }

    private fun place(animateIn: Boolean) {
        if (!chip.isVisible || hiding) return
        val p = placement(chip.width, chip.height)
        chip.animate().cancel()
        if (animateIn) {
            chip.alpha = 0f
            chip.translationX = p.tx
            chip.translationY = p.ty + 8 * density
            chip.scaleX = p.scale * 0.96f; chip.scaleY = p.scale * 0.96f
            chip.animate().alpha(1f).translationY(p.ty).scaleX(p.scale).scaleY(p.scale)
                .setDuration(200).setInterpolator(Motion.emphasized).start()
        } else {
            chip.animate().alpha(1f).translationX(p.tx).translationY(p.ty).scaleX(p.scale).scaleY(p.scale)
                .setDuration(160).setInterpolator(Motion.emphasized).start()
        }
    }
}
