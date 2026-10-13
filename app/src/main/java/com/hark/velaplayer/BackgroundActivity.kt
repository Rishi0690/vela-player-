package com.hark.velaplayer

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.slider.Slider
import java.util.concurrent.Executors

/**
 * v2.18 Background screen (Settings › Appearance › Background, and Theme editor › Background photo).
 *
 * This screen's own window IS the live preview: every change is saved at once ([Backdrop.update]) and re-applied
 * here without recreating; other open screens rebuild on resume ([ThemedActivity]). Built-in wallpapers (Vela
 * Originals + Cinematic collection), "Choose from gallery" (copied into private storage, no permission needed),
 * Clear / Blurred glass, blur intensity and dim sliders, and "Use theme colors" to go back to the plain atmosphere.
 */
class BackgroundActivity : ThemedActivity() {
    private val io = Executors.newSingleThreadExecutor()
    private val tiles = ArrayList<Tile>()
    private lateinit var styleGroup: MaterialButtonToggleGroup
    private lateinit var blurBlock: LinearLayout
    private lateinit var blurValue: TextView
    private lateinit var dimValue: TextView
    private lateinit var blurSlider: Slider
    private lateinit var dimSlider: Slider
    private lateinit var appearanceBlock: LinearLayout
    private lateinit var customGrid: LinearLayout
    private var applyToken = 0

    private val pick = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        Toast.makeText(this, "Setting your photo…", Toast.LENGTH_SHORT).show()
        val app = applicationContext
        io.execute {
            val src = Backdrop.importCustom(app, uri)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (src == null) { Toast.makeText(this, "Couldn't open that photo. Try another one.", Toast.LENGTH_LONG).show(); return@runOnUiThread }
                Backdrop.update(this, source = src, stamp = System.currentTimeMillis())
                buildCustomGrid()
                applyNow()
            }
        }
    }

    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; fitsSystemWindows = true }
        val toolbar = MaterialToolbar(this).apply {
            title = "Background"; setNavigationIcon(R.drawable.ic_arrow_back); setNavigationOnClickListener { finish() }
        }
        root.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56f)))
        val scroll = NestedScrollView(this).apply { isVerticalScrollBarEnabled = true }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16f), 0, dp(16f), dp(28f)) }
        scroll.addView(col)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        col.addView(note("Your background shows behind every screen: library, folders, search, settings and the vault. Changes apply instantly."))
        // v2.18.1: Restore to default (theme background + default appearance).
        col.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Restore to default"; setIconResource(R.drawable.ic_restore_default)
            setOnClickListener { Motion.haptic(it); confirmRestore() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52f)).apply { topMargin = dp(12f) })

        // ---- Appearance
        appearanceBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        appearanceBlock.addView(section("Appearance"))
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = Glass.elevatedPanel(context); setPadding(dp(16f), dp(14f), dp(16f), dp(10f))
        }
        styleGroup = MaterialButtonToggleGroup(this).apply { isSingleSelection = true; isSelectionRequired = true }
        val bClear = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { id = View.generateViewId(); text = "Clear" }
        val bGlass = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { id = View.generateViewId(); text = "Blurred glass" }
        styleGroup.addView(bClear, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        styleGroup.addView(bGlass, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(styleGroup, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        blurBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val (blurHead, bv) = sliderHeader("Blur intensity"); blurValue = bv
        blurSlider = Slider(this).apply { valueFrom = 0f; valueTo = 100f; stepSize = 1f; contentDescription = "Blur intensity" }
        blurBlock.addView(blurHead); blurBlock.addView(blurSlider)
        panel.addView(blurBlock)
        val (dimHead, dv) = sliderHeader("Dim"); dimValue = dv
        dimSlider = Slider(this).apply { valueFrom = 0f; valueTo = 90f; stepSize = 1f; contentDescription = "Dim" }
        panel.addView(dimHead); panel.addView(dimSlider)
        panel.addView(note("Vela always adds just enough shade to keep text and icons readable.").apply { setPadding(0, 0, 0, dp(6f)) })
        appearanceBlock.addView(panel)
        col.addView(appearanceBlock)

        val s = Backdrop.spec(this)
        styleGroup.check(if (s.glass) bGlass.id else bClear.id)
        blurSlider.value = s.blur.toFloat(); dimSlider.value = s.dim.toFloat()
        updateLabels()
        styleGroup.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            Backdrop.update(this, style = if (id == bGlass.id) Backdrop.STYLE_GLASS else Backdrop.STYLE_CLEAR)
            updateLabels(); applyNow()
        }
        blurSlider.addOnChangeListener { _, v, fromUser -> if (fromUser) { Backdrop.update(this, blur = v.toInt()); updateLabels(); applyNow() } }
        dimSlider.addOnChangeListener { _, v, fromUser -> if (fromUser) { Backdrop.update(this, dim = v.toInt()); updateLabels(); applyNow() } }

        // ---- Your photo
        col.addView(section("Your photo"))
        customGrid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(customGrid)
        buildCustomGrid()

        // ---- Built-ins
        col.addView(section("Vela Originals"))
        col.addView(grid(listOf(themeTile()) + Backdrop.BUILTINS.filter { it.original }.map { builtinTile(it) }))
        col.addView(section("Cinematic collection"))
        col.addView(grid(Backdrop.BUILTINS.filter { !it.original }.map { builtinTile(it) }))

        refreshSelection()
        loadThumbs()
    }

    override fun onDestroy() { super.onDestroy(); io.shutdown() }

    // ------------------------------------------------------------------ apply

    /** Processes off the main thread, then swaps this window's backdrop (latest change wins). */
    private fun applyNow() {
        val token = ++applyToken
        refreshSelection()
        val app = applicationContext
        io.execute {
            Backdrop.processed(app)
            runOnUiThread { if (token == applyToken && !isDestroyed) backdropChangedHere() }
        }
    }

    private fun select(source: String) {
        if (Backdrop.spec(this).source == source) return
        Backdrop.update(this, source = source)
        applyNow()
    }

    private fun updateLabels() {
        val s = Backdrop.spec(this)
        blurBlock.isVisible = s.glass
        blurValue.text = "${s.blur}%"
        dimValue.text = "${s.dim}%"
    }

    private fun refreshSelection() {
        val src = if (Backdrop.active(this)) Backdrop.spec(this).source else ""
        tiles.forEach { it.setSelectedState(it.source == src) }
        appearanceBlock.alpha = if (src.isEmpty()) 0.5f else 1f
        val enabled = src.isNotEmpty()
        for (i in 0 until styleGroup.childCount) styleGroup.getChildAt(i).isEnabled = enabled
        blurSlider.isEnabled = enabled; dimSlider.isEnabled = enabled
    }

    // ------------------------------------------------------------------ tiles

    private fun buildCustomGrid() {
        customGrid.removeAllViews()
        tiles.removeAll { Backdrop.isCustom(it.source) || it.source == PICK }
        val list = ArrayList<Tile>()
        list += Tile(this, PICK, "Choose photo").apply {
            setIcon(R.drawable.ic_add)
            setOnClickListener { Motion.haptic(it); pick.launch("image/*") }
        }
        val customs = Backdrop.customs(this)
        customs.forEachIndexed { i, src ->
            list += Tile(this, src, if (customs.size == 1) "Your photo" else "Your photo ${customs.size - i}").apply {
                setOnClickListener { Motion.haptic(it); select(src) }
                setOnLongClickListener { Motion.haptic(it); confirmDelete(src); true }
                setDeleteAction { confirmDelete(src) }
            }
        }
        customGrid.addView(grid(list, register = false))
        tiles += list
        val app = applicationContext
        list.filter { Backdrop.isCustom(it.source) }.forEach { t ->
            io.execute { val bm = Backdrop.thumb(app, t.source, 320); runOnUiThread { if (!isDestroyed) t.setImage(bm) } }
        }
        if (customs.isNotEmpty()) customGrid.addView(note("Tap the bin on a photo (or long-press it) to remove it from the app."))
        refreshSelectionSafe()
    }

    private fun confirmDelete(src: String) {
        VelaDialogBuilder(this)
            .setTitle("Delete this photo?")
            .setMessage(if (Backdrop.spec(this).source == src) "It's your current background, so Vela will switch to the default background." else "It will be removed from your backgrounds. The original stays in your gallery.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                val wasActive = Backdrop.spec(this).source == src
                Backdrop.deleteCustom(this, src)
                buildCustomGrid()
                if (wasActive) applyNow() else refreshSelection()
                Toast.makeText(this, "Photo deleted", Toast.LENGTH_SHORT).show()
            }.show()
    }

    private fun confirmRestore() {
        VelaDialogBuilder(this)
            .setTitle("Restore default background?")
            .setMessage("Vela goes back to its original theme background with the default appearance. Your photos stay in the collection.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Restore") { _, _ ->
                Backdrop.restoreDefaults(this)
                syncControls()
                applyNow()
                Toast.makeText(this, "Default background restored", Toast.LENGTH_SHORT).show()
            }.show()
    }

    /** Puts the style buttons and sliders back in line with the saved settings (after Restore). */
    private fun syncControls() {
        val s = Backdrop.spec(this)
        styleGroup.check(styleGroup.getChildAt(if (s.glass) 1 else 0).id)
        blurSlider.value = s.blur.toFloat(); dimSlider.value = s.dim.toFloat()
        updateLabels()
    }

    private fun refreshSelectionSafe() { if (::appearanceBlock.isInitialized && ::blurSlider.isInitialized) refreshSelection() }

    private fun themeTile() = Tile(this, "", "Theme colors").apply {
        image.background = AtmosphereDrawable(Atmosphere.of(context))
        setOnClickListener { Motion.haptic(it); select("") }
    }

    private fun builtinTile(b: Backdrop.Builtin) = Tile(this, "b:" + b.id, b.label).apply {
        setOnClickListener { Motion.haptic(it); select("b:" + b.id) }
    }

    private fun loadThumbs() {
        val app = applicationContext
        val list = tiles.filter { it.source.startsWith("b:") }
        io.execute {
            for (t in list) {
                val bm = Backdrop.thumb(app, t.source, 320)
                runOnUiThread { if (!isDestroyed) t.setImage(bm) }
            }
        }
    }

    /** Rows of three 9:16 tiles. */
    private fun grid(items: List<Tile>, register: Boolean = true): LinearLayout {
        val g = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        items.chunked(3).forEach { row ->
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (k in 0 until 3) {
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = if (k == 0) 0 else dp(5f); marginEnd = if (k == 2) 0 else dp(5f); bottomMargin = dp(10f)
                }
                val t = row.getOrNull(k)
                if (t != null) { r.addView(t, lp); if (register) tiles += t } else r.addView(View(this), lp)
            }
            g.addView(r)
        }
        return g
    }

    private fun section(t: String) = TextView(this).apply {
        text = t
        androidx.core.widget.TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
        setTextColor(ThemeManager.primary(context))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(22f); bottomMargin = dp(10f) }
    }

    private fun note(t: String) = TextView(this).apply {
        text = t; alpha = 0.75f
        androidx.core.widget.TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
        setTextColor(ThemeManager.attrOr(context, com.google.android.material.R.attr.colorOnSurface, Color.WHITE))
        setPadding(0, dp(6f), 0, 0)
    }

    private fun sliderHeader(label: String): Pair<LinearLayout, TextView> {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(14f), 0, 0) }
        val on = ThemeManager.attrOr(this, com.google.android.material.R.attr.colorOnSurface, Color.WHITE)
        row.addView(TextView(this).apply { text = label; androidx.core.widget.TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_BodyLarge); setTextColor(on) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val v = TextView(this).apply { androidx.core.widget.TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_LabelLarge); setTextColor(ThemeManager.primary(context)) }
        row.addView(v)
        return row to v
    }

    /** A 9:16 rounded thumbnail with a label; selected = accent ring + check. */
    private class Tile(c: Context, val source: String, label: String) : FrameLayout(c) {
        val image = ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        private val ring = View(c)
        private val check = ImageView(c)
        private val icon = ImageView(c)
        private val d = c.resources.displayMetrics.density
        private val radius = 16 * d

        init {
            isClickable = true; isFocusable = true
            contentDescription = label
            background = Glass.panel(c, 16f)
            addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(icon, LayoutParams((36 * d).toInt(), (36 * d).toInt(), Gravity.CENTER).apply { bottomMargin = (12 * d).toInt() })
            icon.visibility = GONE
            val scrim = View(c).apply {
                background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xB3000000.toInt(), 0x00000000))
            }
            addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, (44 * d).toInt(), Gravity.BOTTOM))
            addView(TextView(c).apply {
                text = label; setTextColor(Color.WHITE); textSize = 12f; maxLines = 1
                setShadowLayer(4f, 0f, 1f, 0x99000000.toInt())
                setPadding((8 * d).toInt(), 0, (8 * d).toInt(), (7 * d).toInt())
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
            addView(ring, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            val accent = ThemeManager.primary(c)
            ring.background = GradientDrawable().apply { cornerRadius = radius; setStroke((2.5f * d).toInt(), accent) }
            ring.visibility = GONE
            check.setImageResource(R.drawable.ic_check)
            check.imageTintList = ColorStateList.valueOf(if (androidx.core.graphics.ColorUtils.calculateLuminance(accent) > 0.5) Color.BLACK else Color.WHITE)
            check.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(accent) }
            val pad = (3 * d).toInt(); check.setPadding(pad, pad, pad, pad)
            addView(check, LayoutParams((24 * d).toInt(), (24 * d).toInt(), Gravity.TOP or Gravity.END).apply { topMargin = (8 * d).toInt(); marginEnd = (8 * d).toInt() })
            check.visibility = GONE
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, radius) }
            }
            clipToOutline = true
            Motion.pressable(this, 0.96f)
        }

        fun setIcon(res: Int) {
            icon.setImageResource(res); icon.visibility = VISIBLE
            icon.imageTintList = ColorStateList.valueOf(ThemeManager.primary(context))
        }

        /** Small bin badge (top-left) for imported photos. */
        fun setDeleteAction(onDelete: () -> Unit) {
            val bin = ImageView(context).apply {
                setImageResource(R.drawable.ic_delete)
                imageTintList = ColorStateList.valueOf(Color.WHITE)
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x99000000.toInt()) }
                val p = (5 * d).toInt(); setPadding(p, p, p, p)
                contentDescription = "Delete photo"
                setOnClickListener { Motion.haptic(it); onDelete() }
            }
            addView(bin, LayoutParams((30 * d).toInt(), (30 * d).toInt(), Gravity.TOP or Gravity.START).apply { topMargin = (7 * d).toInt(); marginStart = (7 * d).toInt() })
        }

        fun setImage(b: Bitmap?) { if (b != null) image.setImageBitmap(b) }

        fun setSelectedState(on: Boolean) {
            ring.visibility = if (on) VISIBLE else GONE
            check.visibility = if (on) VISIBLE else GONE
            isSelected = on
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(w * 16 / 9, MeasureSpec.EXACTLY))
        }
    }

    private companion object { const val PICK = "#pick" }
}
