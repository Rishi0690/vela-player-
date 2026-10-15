package com.hark.velaplayer

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.transition.ChangeBounds
import android.transition.TransitionManager
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.NestedScrollView
import androidx.core.widget.TextViewCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.radiobutton.MaterialRadioButton

/**
 * v2.14 widget detail / configuration. Opened from the gallery (with [EXTRA_KIND]: set up, then "Add to home screen"
 * through the launcher's own pin dialog) or by the launcher as the widget's configure / reconfigure activity
 * (with EXTRA_APPWIDGET_ID: "Save"). A large live preview re-renders as options change.
 */
class WidgetDetailActivity : ThemedActivity() {
    companion object { const val EXTRA_KIND = "vela_widget_kind" }

    private lateinit var kind: WidgetKind
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private val configuring get() = widgetId != AppWidgetManager.INVALID_APPWIDGET_ID
    private var cfg = WidgetConfig()
    private lateinit var frame: WidgetPreviewFrame
    private lateinit var stage: WidgetStage
    private var sizeIndex = 0
    private val main = Handler(Looper.getMainLooper())
    private val rerender = Runnable { WidgetPreview.render(this, kind, cfg, frame.wDp, frame.hDp, frame, animate = true) }
    private val d get() = resources.displayMetrics.density
    private fun px(v: Float) = (v * d).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        // Configure contract: backing out leaves things as they were.
        if (configuring) setResult(Activity.RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        val k = if (configuring) {
            val info = try { AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId) } catch (_: Exception) { null }
            WidgetKind.forProvider(info?.provider?.className)
        } else WidgetKind.of(intent.getStringExtra(EXTRA_KIND))
        if (k == null) { finish(); return }
        kind = k
        cfg = if (configuring && WidgetStore.has(this, widgetId)) WidgetStore.get(this, widgetId) else WidgetStore.peekPending(this, k) ?: WidgetConfig()
        sizeIndex = when (k) { WidgetKind.NOW_PLAYING -> 2; WidgetKind.LIBRARY -> 1; else -> 0 }
        build()
    }

    private fun build() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bar = MaterialToolbar(this).apply {
            title = kind.title; subtitle = if (configuring) "Widget settings" else "Widget"
            setNavigationIcon(R.drawable.ic_arrow_back); setNavigationOnClickListener { finish() }; setNavigationContentDescription("Back")
        }
        val tv = TypedValue()
        val abh = if (theme.resolveAttribute(androidx.appcompat.R.attr.actionBarSize, tv, true)) TypedValue.complexToDimensionPixelSize(tv.data, resources.displayMetrics) else px(56f)
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, abh))
        val scroll = NestedScrollView(this).apply { clipToPadding = false }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(16f), px(4f), px(16f), px(24f)) }
        scroll.addView(col)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Large live preview
        stage = WidgetStage(this).apply { val p = px(22f); setPadding(p, px(26f), p, px(26f)) }
        frame = WidgetPreviewFrame(this)
        val (_, w, h) = kind.sizes[sizeIndex]
        frame.setSizeDp(w, h)
        stage.addView(frame, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        col.addView(stage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Size switcher (preview only: the real size is set by resizing on the home screen)
        val sizes = ChipGroup(this).apply { isSingleSelection = true; isSelectionRequired = true; chipSpacingHorizontal = px(6f); setPadding(0, px(12f), 0, 0) }
        kind.sizes.forEachIndexed { i, (label, sw, sh) ->
            sizes.addView(Chip(this, null, com.google.android.material.R.attr.chipStyle).apply {
                id = View.generateViewId(); text = label; isCheckable = true; isChecked = i == sizeIndex
                setOnClickListener {
                    if (sizeIndex == i) return@setOnClickListener
                    sizeIndex = i
                    Motion.haptic(this)
                    if (Motion.enabled(context)) TransitionManager.beginDelayedTransition(stage, ChangeBounds().setDuration(Motion.LONG).setInterpolator(Motion.emphasized))
                    frame.setSizeDp(sw, sh)
                    main.removeCallbacks(rerender); main.post(rerender)
                }
            })
        }
        col.addView(sizes)
        col.addView(caption("Preview size. On your home screen, long-press the widget and drag its edges to resize; the layout follows."))

        // Style
        col.addView(section("Style"))
        val styleBox = panel()
        val accentRow = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; visibility = if (cfg.followTheme) View.GONE else View.VISIBLE }
        switchRow(styleBox, "Follow app theme", "Colours change with your theme, custom colours and light/dark mode", cfg.followTheme) { on ->
            cfg = cfg.copy(followTheme = on, accent = if (!on && cfg.accent == 0) ThemeManager.primary(this) else cfg.accent)
            if (Motion.enabled(this)) TransitionManager.beginDelayedTransition(styleBox)
            accentRow.visibility = if (on) View.GONE else View.VISIBLE
            changed()
        }
        accentRow.addView(swatches())
        styleBox.addView(accentRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = px(8f) })
        styleBox.addView(label("Glass"))
        styleBox.addView(toggle(WidgetConfig.OPACITY_LABELS, cfg.opacity) { cfg = cfg.copy(opacity = it); changed() })
        styleBox.addView(label("Corners"))
        styleBox.addView(toggle(WidgetConfig.CORNER_LABELS, WidgetConfig.CORNER_KEYS.indexOf(cfg.corners).coerceAtLeast(0)) { cfg = cfg.copy(corners = WidgetConfig.CORNER_KEYS[it]); changed() })
        col.addView(styleBox)

        // Content
        val content = contentOptions()
        if (content != null) { col.addView(section(if (kind == WidgetKind.QUICK) "Shortcuts" else if (kind == WidgetKind.COLLECTION) "Show" else "Content")); col.addView(content) }

        val existing = kind.ids(this).size
        if (!configuring && existing > 0) col.addView(caption(if (existing == 1) "You already have this widget on your home screen. To change it, long-press it there and choose its settings."
            else "You have $existing of these on your home screen. To change one, long-press it there and choose its settings."))

        // Bottom action
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(px(16f), px(10f), px(16f), px(14f))
            background = Glass.floatingPanel(context, 0f)
        }
        val primary = MaterialButton(this).apply {
            text = if (configuring) "Save widget" else "Add to home screen"
            setIconResource(if (configuring) R.drawable.ic_check else R.drawable.ic_add)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            minimumHeight = px(52f)
            setOnClickListener { Motion.haptic(it); if (configuring) save() else pin() }
        }
        bottom.addView(primary, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(bottom, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)
        // v2.20 edge to edge: the glass action panel runs behind the nav bar, its button stays above it.
        EdgeToEdge.root(root)
        EdgeToEdge.pad(bottom, bottom = true)
        Motion.pressable(primary, 0.97f)

        WidgetPreview.render(this, kind, cfg, frame.wDp, frame.hDp, frame, animate = true)
        if (Motion.enabled(this)) {
            stage.alpha = 0f; stage.translationY = 24 * d; stage.scaleX = 0.97f; stage.scaleY = 0.97f
            stage.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(Motion.XLONG).setInterpolator(Motion.springSoft).start()
            Motion.stagger((2 until col.childCount).map { col.getChildAt(it) }, 10f, 140L, 0.98f)
        }
    }

    private fun changed() { main.removeCallbacks(rerender); main.postDelayed(rerender, 90) }

    // ------------------------------------------------------------------ actions

    private fun save() {
        WidgetStore.takePending(this, kind) // a launcher that opens settings right after "Add" already used it here
        WidgetStore.put(this, widgetId, cfg)
        WidgetUpdater.render(this, kind, intArrayOf(widgetId), null, dataChanged = true)
        setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        finish()
    }

    private fun pin() {
        val mgr = AppWidgetManager.getInstance(this)
        WidgetStore.putPending(this, kind, cfg) // adopted by the new widget (pin callback or its first update)
        if (Build.VERSION.SDK_INT >= 26 && mgr.isRequestPinAppWidgetSupported) {
            val cb = PendingIntent.getBroadcast(this, kind.ordinal,
                Intent(this, WidgetActionReceiver::class.java).setAction(WidgetActionReceiver.ACTION_PINNED).putExtra(WidgetActionReceiver.EXTRA_KIND, kind.key),
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0))
            val ok = try { mgr.requestPinAppWidget(kind.component(this), null, cb) } catch (_: Exception) { false }
            if (ok) { Toast.makeText(this, "Confirm in the dialog to place it on your home screen", Toast.LENGTH_LONG).show(); return }
        }
        VelaDialogBuilder(this)
            .setTitle("Add it from your home screen")
            .setMessage("Your launcher doesn't let apps place widgets directly.\n\n1. Long-press an empty spot on your home screen.\n2. Tap Widgets.\n3. Find Vela Player and drag “${kind.title}” into place.\n\nThe style you chose here is saved and will be used.")
            .setPositiveButton("Got it", null)
            .show()
    }

    // ------------------------------------------------------------------ option builders

    private fun contentOptions(): View? = when (kind) {
        WidgetKind.NOW_PLAYING -> panel().also { p ->
            switchRow(p, "Progress bar", "Elapsed time and a progress line", cfg.showProgress) { cfg = cfg.copy(showProgress = it); changed() }
            switchRow(p, "Previous and next", "Shown while a queue is playing", cfg.showSkip) { cfg = cfg.copy(showSkip = it); changed() }
        }
        WidgetKind.CONTINUE, WidgetKind.LIBRARY -> panel().also { p ->
            switchRow(p, "Progress on thumbnails", "A thin line shows how much you've watched", cfg.showProgress) { cfg = cfg.copy(showProgress = it); changed() }
        }
        WidgetKind.QUICK -> shortcutPicker()
        WidgetKind.COLLECTION -> sourcePicker()
    }

    @SuppressLint("SetTextI18n")
    private fun shortcutPicker(): View {
        val p = panel()
        p.addView(caption("Pick up to four, in the order you tap them. The first one is highlighted."))
        val group = ChipGroup(this).apply { chipSpacingHorizontal = px(6f); setPadding(0, px(6f), 0, px(4f)) }
        val chips = LinkedHashMap<Shortcut, Chip>()
        fun relabel() = chips.forEach { (sc, chip) ->
            val n = cfg.shortcuts.indexOf(sc.key)
            chip.text = if (n >= 0) "${n + 1} · ${sc.label}" else sc.label
            chip.isChecked = n >= 0
        }
        Shortcut.values().forEach { sc ->
            val chip = Chip(this, null, com.google.android.material.R.attr.chipStyle).apply {
                isCheckable = true; setChipIconResource(sc.icon); isChipIconVisible = true
                chipIconTint = android.content.res.ColorStateList.valueOf(ThemeManager.primary(context))
                setOnClickListener {
                    val cur = cfg.shortcuts.toMutableList()
                    if (sc.key in cur) { if (cur.size > 1) cur.remove(sc.key) }
                    else if (cur.size < 4) cur.add(sc.key)
                    else { Toast.makeText(context, "Four shortcuts fit. Tap one to remove it first.", Toast.LENGTH_SHORT).show() }
                    cfg = cfg.copy(shortcuts = cur); relabel(); changed()
                }
            }
            chips[sc] = chip; group.addView(chip)
        }
        relabel()
        p.addView(group)
        p.addView(caption("Vault opens behind its own lock (PIN, pattern or fingerprint), exactly like the hidden entry."))
        return p
    }

    private fun sourcePicker(): View {
        val p = panel()
        val options = ArrayList<Pair<String, String>>()
        options += "favorites" to "Favorites"
        Store.playlists(this).keys.forEach { options += "playlist:$it" to it }
        if (cfg.source.startsWith("playlist:") && options.none { it.first == cfg.source }) cfg = cfg.copy(source = "favorites")
        val radios = ArrayList<MaterialRadioButton>()
        options.forEach { (key, name) ->
            val count = (if (key == "favorites") Store.favorites(this) else Store.playlists(this)[key.removePrefix("playlist:")])?.size ?: 0
            val rb = MaterialRadioButton(this).apply {
                text = "$name  ·  " + (if (count == 1) "1 video" else "$count videos")
                isChecked = cfg.source == key
                setOnClickListener {
                    radios.forEach { it.isChecked = it == this }
                    cfg = cfg.copy(source = key); changed()
                }
            }
            radios += rb; p.addView(rb)
        }
        if (options.size == 1) p.addView(caption("Create a playlist in Vela to show it here."))
        switchRow(p, "Progress on thumbnails", "For videos you started", cfg.showProgress) { cfg = cfg.copy(showProgress = it); changed() }
        return p
    }

    private fun swatches(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, px(4f), 0, px(4f)) }
        val seeds = LinkedHashSet<Int>()
        seeds += ThemeManager.primary(this)
        ThemeManager.customThemes(this).take(4).forEach { seeds += it.seed or 0xFF000000.toInt() }
        ThemeManager.presets.forEach { seeds += it.seed or 0xFF000000.toInt() }
        val views = ArrayList<View>()
        fun ring(v: View, color: Int, on: Boolean) {
            v.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(color)
                if (on) setStroke(px(3f), ThemeManager.attr(this@WidgetDetailActivity, com.google.android.material.R.attr.colorOnSurface)) else setStroke(px(1f), Glass.stroke(this@WidgetDetailActivity))
            }
        }
        seeds.take(18).forEach { color ->
            val v = View(this).apply {
                contentDescription = "Accent ${ThemeManager.hex(color)}"
                setOnClickListener { cfg = cfg.copy(accent = color); views.forEachIndexed { i, sv -> ring(sv, seeds.elementAt(i), sv == this) }; Motion.bounce(this); changed() }
            }
            ring(v, color, cfg.accent == color)
            views += v
            row.addView(v, LinearLayout.LayoutParams(px(34f), px(34f)).apply { marginEnd = px(10f) })
        }
        return row
    }

    private fun panel() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; background = Glass.elevatedPanel(context)
        setPadding(px(14f), px(8f), px(14f), px(10f))
    }

    private fun section(s: String) = TextView(this).apply {
        text = s.uppercase(); TextViewCompat.setTextAppearance(this, R.style.TextAppearance_Vela_Section)
        setPadding(px(4f), px(22f), 0, px(8f))
    }
    private fun label(s: String) = TextView(this).apply {
        text = s; TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
        setPadding(px(2f), px(10f), 0, px(6f))
    }
    private fun caption(s: String) = TextView(this).apply {
        text = s; TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
        alpha = 0.7f; setPadding(px(4f), px(6f), px(4f), px(2f))
    }

    private fun switchRow(parent: LinearLayout, title: String, summary: String, checked: Boolean, onChange: (Boolean) -> Unit): Int {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, px(6f), 0, px(6f)) }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply { text = title; TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_BodyLarge) })
        texts.addView(TextView(this).apply { text = summary; alpha = 0.7f; TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_BodySmall) })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sw = MaterialSwitch(this).apply { isChecked = checked; setOnCheckedChangeListener { _, on -> onChange(on) } }
        row.addView(sw)
        row.setOnClickListener { sw.toggle() }
        parent.addView(row)
        return parent.childCount
    }

    private fun toggle(labels: List<String>, selected: Int, onSelect: (Int) -> Unit): View {
        val g = MaterialButtonToggleGroup(this).apply { isSingleSelection = true; isSelectionRequired = true }
        val ids = labels.mapIndexed { i, l ->
            val b = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                id = View.generateViewId(); text = l; isAllCaps = false; textSize = 13f
                insetTop = 0; insetBottom = 0; minHeight = px(42f)
            }
            g.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            b.id
        }
        g.check(ids[selected.coerceIn(0, ids.lastIndex)])
        g.addOnButtonCheckedListener { _, id, checked -> if (checked) onSelect(ids.indexOf(id)) }
        return g
    }
}
