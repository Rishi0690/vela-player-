package com.hark.velaplayer

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.TextureView
import android.widget.FrameLayout
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Hosts the video TextureView and sizes it for the selected AspectMode.
 * The surface itself is resized (not just a visual overlay), and pinch-zoom / pan
 * are applied as transforms on top. Changing mode never touches the player, so
 * playback position and audio are untouched.
 */
class ZoomableVideoLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    FrameLayout(context, attrs) {

    val textureView = TextureView(context)
    private var videoW = 0
    private var videoH = 0
    private var pixelRatio = 1f
    private var contentW = 0
    private var contentH = 0
    private var panX = 0f
    private var panY = 0f

    var userScale = 1f
        private set

    var mode: AspectMode = AspectMode.FIT
        set(value) {
            field = value
            userScale = 1f; panX = 0f; panY = 0f
            requestLayout()
        }

    init {
        setBackgroundColor(Color.BLACK)
        addView(textureView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun setVideoSize(w: Int, h: Int, par: Float) {
        val p = if (par > 0f) par else 1f
        if (w == videoW && h == videoH && p == pixelRatio) return
        videoW = w; videoH = h; pixelRatio = p
        requestLayout()
    }

    /** Display aspect ratio of the source, including non-square pixels. */
    val videoAspect: Float get() = if (videoW > 0 && videoH > 0) videoW * pixelRatio / videoH else 16f / 9f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        computeContentSize(w, h)
        textureView.measure(
            MeasureSpec.makeMeasureSpec(contentW, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(contentH, MeasureSpec.EXACTLY)
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        val left = (w - contentW) / 2
        val top = (h - contentH) / 2
        textureView.layout(left, top, left + contentW, top + contentH)
        applyTransform()
    }

    private fun computeContentSize(w: Int, h: Int) {
        if (w == 0 || h == 0) { contentW = w; contentH = h; return }
        val screen = w.toFloat() / h
        val va = videoAspect
        fun fit(r: Float) {
            if (screen > r) { contentH = h; contentW = (h * r).roundToInt() } else { contentW = w; contentH = (w / r).roundToInt() }
        }
        fun cover(r: Float) {
            if (screen > r) { contentW = w; contentH = (w / r).roundToInt() } else { contentH = h; contentW = (h * r).roundToInt() }
        }
        when (mode) {
            AspectMode.FIT, AspectMode.AUTO -> fit(va)
            AspectMode.STRETCH -> { contentW = w; contentH = h }
            AspectMode.ZOOM -> cover(va)
            // Fill: covers the screen, allowing up to 20% shape change instead of cropping,
            // so it fills with less lost picture than Zoom and less distortion than Stretch.
            AspectMode.FILL -> cover(screen.coerceIn(va / 1.2f, va * 1.2f))
            AspectMode.ORIGINAL -> {
                val ow = (videoW * pixelRatio).roundToInt()
                val oh = videoH
                if (ow <= 0 || oh <= 0 || ow > w || oh > h) fit(va) else { contentW = ow; contentH = oh }
            }
            AspectMode.R16_9 -> fit(16f / 9f)
            AspectMode.R4_3 -> fit(4f / 3f)
            AspectMode.R21_9 -> fit(21f / 9f)
        }
    }

    fun setUserScale(scale: Float) {
        val s = scale.coerceIn(0.5f, 6f)
        val f = s / userScale
        panX *= f; panY *= f
        userScale = s
        applyTransform()
    }

    fun panBy(dx: Float, dy: Float) { panX += dx; panY += dy; applyTransform() }

    val isZoomed: Boolean get() = userScale > 1.01f || userScale < 0.99f

    fun resetZoom(animate: Boolean = true) {
        userScale = 1f; panX = 0f; panY = 0f
        if (animate) textureView.animate().scaleX(1f).scaleY(1f).translationX(0f).translationY(0f).setDuration(180).start()
        else applyTransform()
    }

    private fun applyTransform() {
        val sw = contentW * userScale
        val sh = contentH * userScale
        val maxX = max(0f, (sw - width) / 2f)
        val maxY = max(0f, (sh - height) / 2f)
        panX = panX.coerceIn(-maxX, maxX)
        panY = panY.coerceIn(-maxY, maxY)
        textureView.scaleX = userScale; textureView.scaleY = userScale
        textureView.translationX = panX; textureView.translationY = panY
    }
}
