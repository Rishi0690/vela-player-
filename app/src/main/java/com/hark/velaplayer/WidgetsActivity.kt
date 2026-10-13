package com.hark.velaplayer

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import androidx.core.widget.TextViewCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import java.util.concurrent.Executors

/**
 * v2.14 Widgets gallery (Settings › Widgets, and Home › ⋮ › Widgets). Every card shows a LIVE preview: the real
 * widget layout rendered by [WidgetRenderer] with the user's real data and the current theme, applied in-app.
 */
class WidgetsActivity : ThemedActivity() {
    private lateinit var cards: LinearLayout
    private val frames = HashMap<WidgetKind, WidgetPreviewFrame>()
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        fun px(v: Float) = (v * dp).toInt()

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; fitsSystemWindows = true }
        val bar = MaterialToolbar(this).apply {
            title = "Widgets"; setNavigationIcon(R.drawable.ic_arrow_back); setNavigationOnClickListener { finish() }
            setNavigationContentDescription("Back")
        }
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, actionBarSize()))
        val scroll = NestedScrollView(this).apply { isVerticalScrollBarEnabled = true; clipToPadding = false }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(16f), px(4f), px(16f), px(32f)) }
        scroll.addView(col)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        // Hero: theme-tinted Vela artwork in a glass panel.
        val hero = FrameLayout(this).apply {
            background = Glass.elevatedPanel(context, Tokens.RADIUS_XL)
            outlineProvider = ViewOutlineProvider.BACKGROUND; clipToOutline = true
        }
        val art = ImageView(this).also { Artwork.applyTo(it) }
        hero.addView(art, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val heroScrim = View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x00000000, Atmosphere.alpha(Atmosphere.of(context).top, 0xB8)))
        }
        hero.addView(heroScrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val heroText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(18f), px(16f), px(18f), px(16f)) }
        heroText.addView(text("Vela on your home screen", com.google.android.material.R.style.TextAppearance_Material3_TitleLarge))
        heroText.addView(text("Live glass widgets that play, resume and follow your theme.", com.google.android.material.R.style.TextAppearance_Material3_BodyMedium, 0.8f))
        heroText.addView(themePill(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(10f) })
        hero.addView(heroText, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        col.addView(hero, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(210f)).apply { topMargin = px(4f) })

        col.addView(section("Choose a widget"))
        cards = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(cards)
        col.addView(text("Widgets use your real videos, playlists and playback. Colours update when you change the theme or light/dark mode. Resize a widget on the home screen to switch between its compact and large layouts.",
            com.google.android.material.R.style.TextAppearance_Material3_BodySmall, 0.65f).apply { setPadding(px(4f), px(18f), px(4f), 0) })

        WidgetKind.values().forEach { k -> cards.addView(card(k)) }

        // Subtle depth: the hero art drifts slower than the page while scrolling (no continuous animation).
        scroll.setOnScrollChangeListener(NestedScrollView.OnScrollChangeListener { _, _, y, _, _ -> art.translationY = y * 0.35f })
        if (Motion.enabled(this)) {
            art.scaleX = 1.08f; art.scaleY = 1.08f
            art.animate().scaleX(1f).scaleY(1f).setDuration(900).setInterpolator(Motion.emphasized).start()
            hero.post { Motion.lightSweep(hero, Atmosphere.alpha(ThemeManager.primary(this), 0x40), 900L, 180L, radius = 28 * resources.displayMetrics.density) }
            Motion.stagger((0 until cards.childCount).map { cards.getChildAt(it) }, 16f, 120L, 0.96f)
        }
    }

    override fun onResume() {
        super.onResume()
        // Data may have changed (played something, edited a playlist): previews re-render with the real state.
        if (started) frames.forEach { (k, f) -> renderInto(k, WidgetStore.peekPending(this, k) ?: WidgetConfig(), f) }
        started = true
        refreshBadges()
    }

    private val badges = HashMap<WidgetKind, TextView>()
    private fun refreshBadges() = badges.forEach { (k, t) ->
        val n = k.ids(this).size
        t.visibility = if (n > 0) View.VISIBLE else View.GONE
        t.text = if (n == 1) "On home screen" else "On home screen ×$n"
    }

    private fun actionBarSize(): Int {
        val tv = TypedValue()
        return if (theme.resolveAttribute(androidx.appcompat.R.attr.actionBarSize, tv, true)) TypedValue.complexToDimensionPixelSize(tv.data, resources.displayMetrics)
        else (56 * resources.displayMetrics.density).toInt()
    }

    private fun text(s: String, style: Int, alpha: Float = 1f) = TextView(this).apply {
        text = s; TextViewCompat.setTextAppearance(this, style); this.alpha = alpha
    }

    private fun section(s: String) = TextView(this).apply {
        text = s.uppercase(); TextViewCompat.setTextAppearance(this, R.style.TextAppearance_Vela_Section)
        val dp = resources.displayMetrics.density
        setPadding((4 * dp).toInt(), (24 * dp).toInt(), 0, (10 * dp).toInt())
    }

    private fun themePill(): TextView {
        val sel = ThemeManager.selection(this)
        val name = sel.preset?.label ?: ThemeManager.customThemes(this).firstOrNull { it.id == sel.customId }?.name ?: "Custom colour"
        val dp = resources.displayMetrics.density
        return TextView(this).apply {
            text = "Follows your theme · $name"
            TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_LabelMedium)
            background = Glass.pillButton(context)
            setPadding((12 * dp).toInt(), (6 * dp).toInt(), (14 * dp).toInt(), (6 * dp).toInt())
            val dot = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ThemeManager.primary(context)); setSize((10 * dp).toInt(), (10 * dp).toInt()) }
            setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null)
            compoundDrawablePadding = (8 * dp).toInt()
        }
    }

    @SuppressLint("SetTextI18n")
    private fun card(k: WidgetKind): View {
        val dp = resources.displayMetrics.density
        fun px(v: Float) = (v * dp).toInt()
        val card = MaterialCardView(this, null, com.google.android.material.R.attr.materialCardViewStyle).apply {
            radius = Tokens.RADIUS_L * dp
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = px(14f) }
            isClickable = true; isFocusable = true
            contentDescription = "${k.title} widget"
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(12f), px(12f), px(12f), px(14f)) }
        val stage = WidgetStage(this)
        val (_, w, h) = k.sizes[galleryIndex(k)]
        val frame = WidgetPreviewFrame(this).apply { setSizeDp(w, h) }
        stage.addView(frame, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        frames[k] = frame
        col.addView(stage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(px(4f), px(12f), px(4f), 0) }
        val icon = ImageView(this).apply {
            setImageResource(k.icon); setColorFilter(ThemeManager.primary(context))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Atmosphere.alpha(ThemeManager.primary(context), 0x2E)) }
            setPadding(px(7f), px(7f), px(7f), px(7f))
        }
        titleRow.addView(icon, LinearLayout.LayoutParams(px(32f), px(32f)))
        titleRow.addView(text(k.title, com.google.android.material.R.style.TextAppearance_Material3_TitleMedium).apply { setPadding(px(10f), 0, 0, 0) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val badge = text("", com.google.android.material.R.style.TextAppearance_Material3_LabelSmall).apply {
            background = Glass.pillButton(context, Atmosphere.alpha(ThemeManager.primary(context), 0x26)); setPadding(px(10f), px(4f), px(10f), px(4f)); visibility = View.GONE
        }
        badges[k] = badge
        titleRow.addView(badge)
        col.addView(titleRow)
        col.addView(text(k.blurb, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium, 0.72f).apply { setPadding(px(4f), px(6f), px(4f), 0) })
        val chips = ChipGroup(this).apply { chipSpacingHorizontal = px(6f); setPadding(px(2f), px(6f), 0, 0) }
        k.sizes.forEach { (label, _, _) ->
            chips.addView(Chip(this, null, com.google.android.material.R.attr.chipStyle).apply {
                text = label; isClickable = false; isCheckable = false; isFocusable = false
                chipMinHeight = 28 * dp; textSize = 12f
            })
        }
        col.addView(chips)
        card.addView(col)
        card.setOnClickListener {
            Motion.haptic(it)
            startActivity(Intent(this, WidgetDetailActivity::class.java).putExtra(WidgetDetailActivity.EXTRA_KIND, k.key))
        }
        Motion.pressable(card, 0.97f)
        renderInto(k, WidgetStore.peekPending(this, k) ?: WidgetConfig(), frame)
        return card
    }

    private fun galleryIndex(k: WidgetKind) = when (k) { WidgetKind.NOW_PLAYING -> 2; WidgetKind.LIBRARY -> 1; else -> 0 }

    private fun renderInto(k: WidgetKind, cfg: WidgetConfig, frame: WidgetPreviewFrame) =
        WidgetPreview.render(this, k, cfg, frame.wDp, frame.hDp, frame, animate = started)
}

/** Renders a widget preview off the main thread and applies it into a [WidgetPreviewFrame]. */
object WidgetPreview {
    /** Fallback when the live preview can't be built: the widget's own picker artwork, so the stage is never empty. */
    fun staticPreview(c: Context, k: WidgetKind): View = ImageView(c).apply {
        setImageResource(when (k) {
            WidgetKind.NOW_PLAYING -> R.drawable.wg_preview_now_playing
            WidgetKind.CONTINUE -> R.drawable.wg_preview_continue
            WidgetKind.QUICK -> R.drawable.wg_preview_quick
            WidgetKind.LIBRARY -> R.drawable.wg_preview_library
            WidgetKind.COLLECTION -> R.drawable.wg_preview_collection
        })
        scaleType = ImageView.ScaleType.FIT_CENTER
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun render(a: Activity, k: WidgetKind, cfg: WidgetConfig, wDp: Int, hDp: Int, frame: WidgetPreviewFrame, animate: Boolean) {
        val night = ThemeSwitch.isDark(a)
        val now = if (k == WidgetKind.NOW_PLAYING) NowState.snapshot(a) else null
        val token = Any(); frame.tag = token
        val app = a.applicationContext
        exec.execute {
            val rv = try { WidgetRenderer.build(WidgetRenderer.previewSpec(app, k, cfg, wDp, hDp, night), now) } catch (_: Exception) { null }
            main.post {
                if (frame.tag !== token || a.isDestroyed) return@post
                // v2.16.2: inflate with the APPLICATION context. The activity's inflater carries AppCompat's view
                // factory, which swaps ImageView/TextView for AppCompat subclasses whose overridden setters are not
                // @RemotableViewMethod, so RemoteViews.apply threw and the preview stayed blank.
                val v = rv?.let {
                    try { it.apply(app, frame) } catch (e: Exception) { Log.w("WidgetPreview", "live preview failed for ${k.key}", e); null }
                } ?: staticPreview(a, k)
                frame.show(v, animate && Motion.enabled(a))
            }
        }
    }
}

/** Backdrop behind previews: a dim "wallpaper" in the theme's own atmosphere, so the glass reads like on a home screen. */
class WidgetStage(c: Context) : FrameLayout(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val atmo = Atmosphere.of(c)
    private val accent = ThemeManager.primary(c)
    private val r = Tokens.RADIUS_M * c.resources.displayMetrics.density
    init {
        setWillNotDraw(false)
        val pad = (14 * c.resources.displayMetrics.density).toInt()
        setPadding(pad, pad, pad, pad)
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) { outline.setRoundRect(0, 0, view.width, view.height, r) }
        }
        clipToOutline = true
    }
    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val top = if (atmo.dark) Atmosphere.mix(atmo.top, accent, 0.10f) else Atmosphere.mix(atmo.bottom, accent, 0.18f)
        val bottom = if (atmo.dark) Atmosphere.mix(atmo.bottom, accent, 0.28f) else Atmosphere.mix(atmo.mid, accent, 0.30f)
        p.shader = LinearGradient(0f, 0f, w, h, top, bottom, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, p)
        p.shader = RadialGradient(w * 0.15f, h * 0.1f, maxOf(w, h) * 0.6f, Atmosphere.alpha(atmo.glowA, 0x88), 0, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, p)
        p.shader = RadialGradient(w * 0.9f, h, maxOf(w, h) * 0.55f, Atmosphere.alpha(atmo.glowB, 0x77), 0, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, p)
        super.onDraw(canvas)
    }
}

/**
 * Hosts an applied widget preview at its real dp size, scaled down only when the screen is narrower than the widget.
 * Touches never reach the preview (its buttons would act for real); the card handles taps.
 */
class WidgetPreviewFrame(c: Context) : FrameLayout(c) {
    var wDp = 300; private set
    var hDp = 150; private set
    private val d = c.resources.displayMetrics.density

    fun setSizeDp(w: Int, h: Int) { wDp = w; hDp = h; requestLayout() }

    fun show(v: View, animate: Boolean) {
        val old = if (childCount > 0) getChildAt(0) else null
        addView(v, LayoutParams((wDp * d).toInt(), (hDp * d).toInt()))
        if (old == null) {
            if (animate) { v.alpha = 0f; v.animate().alpha(1f).setDuration(Motion.MEDIUM).setInterpolator(Motion.emphasized).start() }
            return
        }
        if (animate) {
            v.alpha = 0f
            v.animate().alpha(1f).setDuration(Motion.MEDIUM).setInterpolator(Motion.emphasized).start()
            old.animate().alpha(0f).setDuration(Motion.SHORT).withEndAction { removeView(old) }.start()
        } else removeView(old)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent?) = true
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent?) = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val avail = MeasureSpec.getSize(widthMeasureSpec).takeIf { it > 0 } ?: (wDp * d).toInt()
        val wPx = (wDp * d).toInt(); val hPx = (hDp * d).toInt()
        val scale = minOf(1f, avail.toFloat() / wPx)
        for (i in 0 until childCount) {
            val ch = getChildAt(i)
            ch.layoutParams.width = wPx; ch.layoutParams.height = hPx
            ch.measure(MeasureSpec.makeMeasureSpec(wPx, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(hPx, MeasureSpec.EXACTLY))
            ch.pivotX = wPx / 2f; ch.pivotY = 0f
            ch.scaleX = scale; ch.scaleY = scale
        }
        setMeasuredDimension(avail, (hPx * scale).toInt())
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val wPx = (wDp * d).toInt(); val hPx = (hDp * d).toInt()
        val left = (r - l - wPx) / 2
        for (i in 0 until childCount) getChildAt(i).layout(left, 0, left + wPx, hPx)
    }
}
