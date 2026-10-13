package com.hark.velaplayer

import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import com.google.android.material.chip.Chip
import com.hark.velaplayer.databinding.ActivityThemeBinding

/**
 * v2.11 theme gallery. Sections of ready-made glass themes drawn as miniatures in their real colors, a live preview
 * at the top, saved custom themes (edit / duplicate / rename / delete), a custom color with a generated palette and
 * an Advanced section. Tap = preview, tap again or Apply = use it everywhere (open screens recreate with a
 * cross-fade; the playback service is never touched).
 */
class ThemeActivity : ThemedActivity() {
    private lateinit var b: ActivityThemeBinding

    /** What the preview shows: a preset, or a custom color (+ optional secondary accent and background base). */
    private data class Draft(val preset: ThemeManager.Preset?, val seed: Int, val secondary: Int = 0, val base: String = "", val customId: String? = null)

    private lateinit var draft: Draft
    private var editingId: String? = null
    private var updatingHex = false
    private var advancedOpen = false
    private val night get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityThemeBinding.inflate(layoutInflater)
        setContentView(b.root)
        ThemeSwitch.playIfPending(this)
        b.toolbar.setNavigationOnClickListener { finish() }
        b.preview.detailed = true

        val sel = ThemeManager.selection(this)
        draft = Draft(sel.preset, sel.seed, sel.secondary, sel.base, sel.customId)
        savedInstanceState?.let { st ->
            val pk = st.getString("d_preset")
            draft = Draft(ThemeManager.presetByKey(pk), st.getInt("d_seed", draft.seed), st.getInt("d_sec"), st.getString("d_base") ?: "", st.getString("d_id"))
            editingId = st.getString("editing")
            advancedOpen = st.getBoolean("adv")
        }

        b.modeGroup.check(when (Prefs.sp(this).getString("theme", "dark")) { "light" -> R.id.modeLight; "system" -> R.id.modeSystem; else -> R.id.modeDark })
        b.modeGroup.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val v = when (id) { R.id.modeLight -> "light"; R.id.modeSystem -> "system"; else -> "dark" }
            if (v == Prefs.sp(this).getString("theme", "dark")) return@addOnButtonCheckedListener
            Prefs.sp(this).edit().putString("theme", v).apply()
            ThemeManager.touch(this)
            ThemeSwitch.prepareCrossfade(this)
            Prefs.applyTheme(this) // AppCompat recreates this screen in the new mode
        }

        b.picker.onColorChanged = { setCustomDraft(it, fromPicker = true) }
        b.hexInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
            override fun afterTextChanged(e: Editable?) {
                if (updatingHex) return
                val t = e?.toString().orEmpty()
                val c = if (t.length == 6) ThemeManager.parseHex(t) else null
                b.hexLayout.error = if (t.length == 6 && c == null) "Not a valid color" else null
                if (c != null) setCustomDraft(c, fromHex = true)
            }
        })
        b.btnApply.setOnClickListener { applyDraft() }
        b.btnSave.setOnClickListener { saveDialog() }
        b.btnReset.setOnClickListener {
            VelaDialogBuilder(this).setTitle("Reset theme?").setMessage("Go back to Midnight Glass. Your saved themes are kept.")
                .setPositiveButton("Reset") { _, _ -> ThemeManager.presetByKey(ThemeManager.DEFAULT_KEY)?.let { draft = Draft(it, it.seed); editingId = null; applyDraft() } }
                .setNegativeButton("Cancel", null).show()
        }
        b.advancedHeader.setOnClickListener {
            advancedOpen = !advancedOpen
            // v2.12: the Advanced section expands/collapses smoothly instead of snapping.
            if (Motion.enabled(this)) android.transition.TransitionManager.beginDelayedTransition(b.scroll.getChildAt(0) as android.view.ViewGroup,
                android.transition.AutoTransition().setDuration(Motion.MEDIUM).setInterpolator(Motion.emphasized))
            renderAdvancedOpen()
        }
        renderAdvancedOpen()

        if (!ThemeManager.exactCustomColors) {
            b.limitNote.isVisible = true
            b.limitNote.text = "On Android 11 and older, custom colors use the closest built-in palette (Android can only build a full palette from any color on version 12 and newer)."
        }
        buildPresets()
        buildCustoms()
        buildAdvanced()
        syncPickerTo(draft.seed)
        render()
        // v2.12: the gallery cards arrive in a short staggered rise; the live preview sharpens in.
        if (savedInstanceState == null) {
            Motion.stagger(cards.map { it.second }.take(15), 14f, 80, 0.92f)
            Motion.blurIn(b.preview, 16f, Motion.XLONG)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("d_preset", draft.preset?.key); outState.putInt("d_seed", draft.seed)
        outState.putInt("d_sec", draft.secondary); outState.putString("d_base", draft.base); outState.putString("d_id", draft.customId)
        outState.putString("editing", editingId); outState.putBoolean("adv", advancedOpen)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------- draft / preview ----------

    private fun paletteOf(d: Draft): ThemeManager.Palette =
        d.preset?.let { ThemeManager.palette(this, it) } ?: ThemeManager.customPalette(this, d.seed, d.secondary, d.base, night)

    private fun isApplied(d: Draft): Boolean {
        val s = ThemeManager.selection(this)
        return if (d.preset != null) s.preset?.key == d.preset.key
        else s.isCustom && (s.seed or 0xFF000000.toInt()) == (d.seed or 0xFF000000.toInt()) && s.secondary == d.secondary && s.base == d.base
    }

    private fun draftName(d: Draft): String = d.preset?.label
        ?: d.customId?.let { id -> ThemeManager.customThemes(this).firstOrNull { it.id == id }?.name }
        ?: "Custom ${ThemeManager.hex(d.seed)}"

    private fun render() {
        val pal = paletteOf(draft)
        b.preview.palette = pal
        b.previewName.text = draftName(draft)
        val applied = isApplied(draft)
        b.previewState.text = if (applied) "Applied" else "Preview — tap Apply to use it"
        b.btnApply.isEnabled = !applied
        b.btnApply.text = if (applied) "Applied" else "Apply"
        b.swatches.removeAllViews()
        listOf(pal.primary, pal.secondary, pal.tertiary).forEach { c ->
            b.swatches.addView(View(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c); setStroke(dp(1), pal.stroke) }
            }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginStart = dp(6) })
        }
        renderGenerated()
        refreshChecks()
        b.editingNote.isVisible = editingId != null
        b.editingNote.text = editingId?.let { id -> ThemeManager.customThemes(this).firstOrNull { it.id == id }?.let { "Editing \"${it.name}\" — Save to update it" } }
        b.btnSave.text = if (editingId != null) "Save changes" else "Save theme"
    }

    /** v2.12: the live preview cross-dissolves (blur-to-sharp) into the newly picked theme. */
    private fun setDraft(d: Draft) { Motion.crossSwap(b.preview) { draft = d; render() } }

    private fun setCustomDraft(c: Int, fromPicker: Boolean = false, fromHex: Boolean = false) {
        val seed = c or 0xFF000000.toInt()
        draft = Draft(null, seed, draft.takeIf { it.preset == null }?.secondary ?: 0, draft.takeIf { it.preset == null }?.base ?: "", editingId)
        if (!fromPicker) b.picker.color = seed
        if (!fromHex) setHex(seed)
        render()
    }

    private fun syncPickerTo(seed: Int) { b.picker.color = seed or 0xFF000000.toInt(); setHex(seed) }
    private fun setHex(seed: Int) { updatingHex = true; b.hexInput.setText(ThemeManager.hex(seed).removePrefix("#")); updatingHex = false; b.hexLayout.error = null }

    /** The palette generated from the custom color, with a contrast check on the accent button and body text. */
    private fun renderGenerated() {
        val pal = if (draft.preset == null) paletteOf(draft) else ThemeManager.customPalette(this, b.picker.color, 0, "", night)
        b.genRow.removeAllViews()
        listOf("Accent" to pal.primary, "Container" to pal.primaryContainer, "Second" to pal.secondary, "Third" to pal.tertiary,
            "Glass" to ColorUtils.compositeColors(pal.glass, pal.bg), "Base" to pal.bg).forEach { (label, c) ->
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            col.addView(View(this).apply {
                background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(c); setStroke(dp(1), pal.stroke) }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(32)))
            col.addView(TextView(this).apply {
                text = label; gravity = Gravity.CENTER; maxLines = 1
                TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_LabelSmall)
                setPadding(0, dp(3), 0, 0)
            })
            b.genRow.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        }
        val btn = ColorUtils.calculateContrast(pal.onPrimary or 0xFF000000.toInt(), pal.primary or 0xFF000000.toInt())
        val txt = ColorUtils.calculateContrast(pal.text or 0xFF000000.toInt(), pal.bg or 0xFF000000.toInt())
        val ok = btn >= 4.5 && txt >= 4.5
        b.contrastNote.text = String.format("Contrast: button %.1f:1 · text %.1f:1 %s", btn, txt, if (ok) "✓ readable" else "· low, pick a deeper or lighter color")
    }

    private fun applyDraft() {
        val d = draft
        if (d.preset != null) ThemeManager.setPreset(this, d.preset)
        else ThemeManager.setCustom(this, d.seed, d.secondary, d.base, d.customId)
        Toast.makeText(this, "${draftName(d)} applied", Toast.LENGTH_SHORT).show()
        ThemeSwitch.prepareCrossfade(this)
        recreate()
    }

    // ---------- gallery ----------

    private val cards = ArrayList<Pair<(Draft) -> Boolean, View>>()

    private fun buildPresets() {
        b.presetContainer.removeAllViews()
        cards.removeAll { it.second.tag == "preset" }
        ThemeCatalog.sections.forEach { section ->
            val items = ThemeManager.presets.filter { it.section == section }
            if (items.isEmpty()) return@forEach
            b.presetContainer.addView(TextView(this).apply { text = section; accessibilityHeadingCompat() }.also { TextViewCompat.setTextAppearance(it, com.google.android.material.R.style.TextAppearance_Material3_TitleSmall); it.setTextColor(ThemeManager.primary(this)) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(20); bottomMargin = dp(8) })
            grid(b.presetContainer, items.map { p ->
                card(p.label, ThemeManager.palette(this, p), "preset", isMatch = { it.preset?.key == p.key },
                    onTap = { if (draft.preset?.key == p.key) applyDraft() else { editingId = null; setDraft(Draft(p, p.seed)); syncPickerTo(p.seed) } },
                    onLong = null)
            })
        }
    }

    private fun buildCustoms() {
        b.customContainer.removeAllViews()
        cards.removeAll { it.second.tag == "custom" }
        val customs = ThemeManager.customThemes(this)
        b.customEmpty.isVisible = customs.isEmpty()
        grid(b.customContainer, customs.map { t ->
            card(t.name, ThemeManager.customPalette(this, t.seed, t.secondary, t.base, night), "custom",
                isMatch = { it.preset == null && it.customId == t.id },
                onTap = {
                    val d = Draft(null, t.seed, t.secondary, t.base, t.id)
                    if (draft == d) applyDraft() else { setDraft(d); syncPickerTo(t.seed); buildAdvanced() }
                },
                onLong = { v -> customMenu(v, t) })
        })
    }

    private fun grid(container: LinearLayout, views: List<View>) {
        val perRow = if (resources.configuration.screenWidthDp >= 600) 5 else 3
        views.chunked(perRow).forEach { chunk ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEach { v -> row.addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }) }
            repeat(perRow - chunk.size) { row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { setMargins(dp(4), 0, dp(4), 0) }) }
            container.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = -dp(4); marginEnd = -dp(4) })
        }
    }

    private fun card(label: String, pal: ThemeManager.Palette, kind: String, isMatch: (Draft) -> Boolean, onTap: () -> Unit, onLong: ((View) -> Unit)?): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(4))
            background = Glass.panel(this@ThemeActivity, 18f)
            tag = kind
            contentDescription = "$label theme"
            minimumHeight = dp(48)
        }
        val frame = FrameLayout(this)
        frame.addView(ThemePreviewView(this).apply { palette = pal; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        val check = ImageView(this).apply {
            setImageResource(R.drawable.ic_check)
            imageTintList = ColorStateList.valueOf(pal.onPrimary)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(pal.primary) }
            setPadding(dp(3), dp(3), dp(3), dp(3))
            this.tag = "check"
            isVisible = false
        }
        frame.addView(check, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(4), dp(4), 0) })
        col.addView(frame)
        col.addView(TextView(this).apply {
            text = label; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; gravity = Gravity.CENTER
            TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_LabelMedium)
            setPadding(dp(2), dp(6), dp(2), dp(2))
            this.tag = "label"
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        col.setOnClickListener { Motion.bounce(frame); onTap() }
        Motion.pressable(col, 0.95f)
        if (onLong != null) col.setOnLongClickListener { onLong(it); true }
        cards += isMatch to col
        return col
    }

    /** Check = applied theme; accent ring = the one being previewed. */
    private fun refreshChecks() {
        val s = ThemeManager.selection(this)
        val applied = Draft(s.preset, s.seed, s.secondary, s.base, s.customId)
        val primary = ThemeManager.primary(this)
        cards.forEach { (match, v) ->
            val isApplied = match(applied)
            val isDraft = match(draft)
            v.findViewWithTag<View>("check")?.isVisible = isApplied
            v.background = if (isDraft) Glass.panel(this, 18f, stroke = primary) else Glass.panel(this, 18f)
            v.findViewWithTag<TextView>("label")?.alpha = if (isDraft || isApplied) 1f else 0.8f
            v.isSelected = isDraft
            v.stateDescriptionCompat(if (isApplied) "Applied" else if (isDraft) "Previewing" else null)
        }
    }

    private fun View.accessibilityHeadingCompat() { if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true }
    private fun View.stateDescriptionCompat(s: String?) { if (android.os.Build.VERSION.SDK_INT >= 30) stateDescription = s }

    // ---------- saved themes ----------

    private fun customMenu(anchor: View, t: ThemeManager.Custom) {
        val pm = androidx.appcompat.widget.PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, "Apply"); pm.menu.add(0, 2, 1, "Edit"); pm.menu.add(0, 3, 2, "Duplicate")
        pm.menu.add(0, 4, 3, "Rename"); pm.menu.add(0, 5, 4, "Delete")
        pm.setOnMenuItemClickListener { mi ->
            when (mi.itemId) {
                1 -> { draft = Draft(null, t.seed, t.secondary, t.base, t.id); applyDraft() }
                2 -> {
                    editingId = t.id; setDraft(Draft(null, t.seed, t.secondary, t.base, t.id)); syncPickerTo(t.seed); buildAdvanced()
                    b.scroll.post { b.scroll.smoothScrollTo(0, b.customTitle.top - dp(8)) }
                }
                3 -> { ThemeManager.saveCustom(this, t.copy(id = ThemeManager.newId(), name = "${t.name} copy")); buildCustoms(); render() }
                4 -> nameDialog("Rename theme", t.name, "Rename") { n -> ThemeManager.saveCustom(this, t.copy(name = n)); buildCustoms(); render() }
                5 -> VelaDialogBuilder(this).setTitle("Delete \"${t.name}\"?").setMessage("The theme stays applied if it's in use; it's only removed from this list.")
                    .setPositiveButton("Delete") { _, _ ->
                        ThemeManager.deleteCustom(this, t.id)
                        if (editingId == t.id) editingId = null
                        if (draft.customId == t.id) draft = draft.copy(customId = null)
                        buildCustoms(); render()
                    }.setNegativeButton("Cancel", null).show()
            }
            true
        }
        pm.show()
    }

    private fun nameDialog(title: String, initial: String, positive: String, onOk: (String) -> Unit) {
        val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS; hint = "Theme name"; setText(initial); setSelection(text.length) }
        val box = FrameLayout(this).apply { setPadding(dp(20), dp(10), dp(20), 0); addView(input) }
        VelaDialogBuilder(this).setTitle(title).setView(box)
            .setPositiveButton(positive) { _, _ -> onOk(input.text.toString().trim().ifEmpty { initial }) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun saveDialog() {
        val seed = if (draft.preset == null) draft.seed else b.picker.color
        val sec = if (draft.preset == null) draft.secondary else 0
        val base = if (draft.preset == null) draft.base else ""
        val editing = editingId?.let { id -> ThemeManager.customThemes(this).firstOrNull { it.id == id } }
        if (editing != null) {
            VelaDialogBuilder(this).setTitle("Save \"${editing.name}\"?")
                .setPositiveButton("Update & apply") { _, _ ->
                    val t = ThemeManager.saveCustom(this, editing.copy(seed = seed, secondary = sec, base = base))
                    editingId = null; draft = Draft(null, t.seed, t.secondary, t.base, t.id); applyDraft()
                }
                .setNeutralButton("Save as new") { _, _ -> editingId = null; saveNew(seed, sec, base, "${editing.name} 2") }
                .setNegativeButton("Cancel", null).show()
        } else saveNew(seed, sec, base, "My ${ThemeManager.hex(seed)}")
    }

    private fun saveNew(seed: Int, sec: Int, base: String, suggested: String) {
        nameDialog("Save this theme", suggested, "Save & apply") { name ->
            val t = ThemeManager.saveCustom(this, ThemeManager.Custom(ThemeManager.newId(), name, seed, sec, base))
            draft = Draft(null, t.seed, t.secondary, t.base, t.id); applyDraft()
        }
    }

    // ---------- Advanced ----------

    private fun renderAdvancedOpen() {
        b.advancedBody.isVisible = advancedOpen
        b.advancedArrow.text = if (advancedOpen) "Hide" else "Show"
    }

    private fun currentCustom(): Draft = if (draft.preset == null) draft else Draft(null, b.picker.color, 0, "", editingId)

    private fun buildAdvanced() {
        val cur = currentCustom()
        // Secondary accent: Auto + one swatch per distinct theme color.
        b.secondaryRow.removeAllViews()
        val seeds = listOf(0) + ThemeManager.presets.filter { it.key != ThemeManager.DEFAULT_KEY }.map { it.seed }.distinct()
        seeds.forEach { s ->
            val sel = (cur.secondary or 0) == (if (s == 0) 0 else s or 0xFF000000.toInt())
            val v = FrameLayout(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (s == 0) Glass.surface(this@ThemeActivity) else s)
                    setStroke(dp(if (sel) 3 else 1), if (sel) ThemeManager.primary(this@ThemeActivity) else Glass.stroke(this@ThemeActivity))
                }
                contentDescription = if (s == 0) "Automatic secondary accent" else "Secondary accent ${ThemeManager.hex(s)}"
                setOnClickListener {
                    val c = currentCustom()
                    setDraft(c.copy(secondary = if (s == 0) 0 else s or 0xFF000000.toInt())); buildAdvanced()
                }
            }
            if (s == 0) v.addView(TextView(this).apply { text = "A"; gravity = Gravity.CENTER; TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_LabelLarge) },
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            b.secondaryRow.addView(v, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(8) })
        }
        // Background & glass base.
        b.baseGroup.setOnCheckedStateChangeListener(null)
        b.baseGroup.removeAllViews()
        (listOf("" to "Auto") + ThemeManager.presets.map { it.key to it.label }).forEach { (k, label) ->
            val chip = Chip(this, null, com.google.android.material.R.attr.chipStyle).apply {
                text = label; isCheckable = true; isChecked = cur.base == k; this.tag = k; id = View.generateViewId()
            }
            b.baseGroup.addView(chip)
        }
        b.baseGroup.setOnCheckedStateChangeListener { g, ids ->
            val k = ids.firstOrNull()?.let { g.findViewById<View>(it)?.tag as? String } ?: return@setOnCheckedStateChangeListener
            setDraft(currentCustom().copy(base = k))
        }
    }
}
