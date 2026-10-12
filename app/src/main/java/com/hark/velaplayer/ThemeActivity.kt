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
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import com.google.android.material.color.utilities.Scheme
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.hark.velaplayer.databinding.ActivityThemeBinding

/** Theme editor: presets, saved themes, color picker + HEX, light/dark/system, with a live preview. */
class ThemeActivity : ThemedActivity() {
    private lateinit var b: ActivityThemeBinding
    private var draft = ThemeManager.DEFAULT_SEED
    private var updatingHex = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityThemeBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.toolbar.setNavigationOnClickListener { finish() }

        draft = savedInstanceState?.getInt("draft") ?: ThemeManager.seed(this)

        b.modeGroup.check(when (Prefs.sp(this).getString("theme", "dark")) { "light" -> R.id.modeLight; "system" -> R.id.modeSystem; else -> R.id.modeDark })
        b.modeGroup.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val v = when (id) { R.id.modeLight -> "light"; R.id.modeSystem -> "system"; else -> "dark" }
            if (v == Prefs.sp(this).getString("theme", "dark")) return@addOnButtonCheckedListener
            Prefs.sp(this).edit().putString("theme", v).apply()
            ThemeManager.touch(this)
            Prefs.applyTheme(this) // AppCompat recreates this screen in the new mode
        }

        b.picker.onColorChanged = { setDraft(it, fromPicker = true) }
        b.hexInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
            override fun afterTextChanged(e: Editable?) {
                if (updatingHex) return
                val t = e?.toString().orEmpty()
                val c = if (t.length == 6) ThemeManager.parseHex(t) else null
                b.hexLayout.error = if (t.length == 6 && c == null) "Not a valid color" else null
                if (c != null) setDraft(c, fromHex = true)
            }
        })
        b.btnApply.setOnClickListener {
            ThemeManager.setSeed(this, draft)
            Toast.makeText(this, "Theme applied", Toast.LENGTH_SHORT).show()
            recreate()
        }
        b.btnSave.setOnClickListener { saveDialog() }

        if (!ThemeManager.exactCustomColors) {
            b.limitNote.isVisible = true
            b.limitNote.text = "On Android 11 and older, custom colors use the closest built-in palette (Android can only build a full palette from any color on version 12 and newer)."
        }
        buildSwatches()
        setDraft(draft)
    }

    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); outState.putInt("draft", draft) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun setDraft(c: Int, fromPicker: Boolean = false, fromHex: Boolean = false) {
        draft = c or 0xFF000000.toInt()
        if (!fromPicker) b.picker.color = draft
        if (!fromHex) { updatingHex = true; b.hexInput.setText(ThemeManager.hex(draft).removePrefix("#")); updatingHex = false; b.hexLayout.error = null }
        renderPreview()
        refreshChecks()
    }

    /** Colors the preview exactly as the app will look: the Material 3 palette for this color in the current mode. */
    private fun renderPreview() {
        val effective = if (ThemeManager.exactCustomColors || ThemeManager.presetFor(draft) != null) draft else ThemeManager.nearestPreset(draft).seed
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val s = if (night) Scheme.dark(effective) else Scheme.light(effective)
        val primary = ColorStateList.valueOf(s.primary)
        b.previewButton.backgroundTintList = primary
        b.previewButton.setTextColor(s.onPrimary); b.previewButton.iconTint = ColorStateList.valueOf(s.onPrimary)
        b.previewChip.chipBackgroundColor = ColorStateList.valueOf(s.secondaryContainer)
        b.previewChip.setTextColor(s.onSecondaryContainer)
        b.previewChip.checkedIconTint = ColorStateList.valueOf(s.onSecondaryContainer)
        b.previewSwitch.trackTintList = primary
        b.previewSwitch.thumbTintList = ColorStateList.valueOf(s.onPrimary)
        b.previewSlider.trackActiveTintList = primary
        b.previewSlider.thumbTintList = primary
        b.previewSlider.trackInactiveTintList = ColorStateList.valueOf(s.surfaceVariant)
        b.previewProgressBar.setIndicatorColor(s.primary)
        b.previewProgressBar.trackColor = s.surfaceVariant
        b.previewRow.background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(s.primaryContainer) }
        b.previewTitle.setTextColor(s.onPrimaryContainer)
        b.previewThumb.background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(s.surfaceVariant) }
        b.previewPlayIcon.imageTintList = ColorStateList.valueOf(s.onSurfaceVariant)
        b.previewProgress.background = GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(s.primary) }
        b.previewCheck.imageTintList = primary
    }

    private val swatchViews = ArrayList<Pair<Int, View>>()

    private fun buildSwatches() {
        swatchViews.clear()
        fillRow(b.presetContainer, ThemeManager.presets.map { Triple(it.label, it.seed, false) })
        val customs = ThemeManager.customThemes(this)
        b.customEmpty.isVisible = customs.isEmpty()
        fillRow(b.customContainer, customs.map { Triple(it.name, it.seed, true) })
    }

    private fun fillRow(container: LinearLayout, items: List<Triple<String, Int, Boolean>>) {
        container.removeAllViews()
        val perRow = 5
        items.chunked(perRow).forEach { chunk ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEach { (label, seed, deletable) -> row.addView(swatch(label, seed, deletable), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) }
            repeat(perRow - chunk.size) { row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
            container.addView(row)
        }
    }

    private fun swatch(label: String, seed: Int, deletable: Boolean): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, dp(6), 0, dp(6)) }
        val circle = FrameLayout(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(seed) }
            contentDescription = "$label theme"
            foreground = getDrawable(R.drawable.bg_circle)
        }
        val check = ImageView(this).apply {
            setImageResource(R.drawable.ic_check)
            imageTintList = ColorStateList.valueOf(if (androidx.core.graphics.ColorUtils.calculateLuminance(seed) > 0.55) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
            tag = "check"
        }
        circle.addView(check, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        col.addView(circle, LinearLayout.LayoutParams(dp(48), dp(48)))
        col.addView(TextView(this).apply {
            text = label; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; gravity = Gravity.CENTER
            TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_LabelSmall)
            setPadding(dp(2), dp(4), dp(2), 0)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        col.setOnClickListener { Motion.bounce(circle); setDraft(seed) }
        Motion.pressable(col, 0.92f)
        if (deletable) col.setOnLongClickListener {
            MaterialAlertDialogBuilder(this).setTitle("Delete \"$label\"?")
                .setPositiveButton("Delete") { _, _ -> ThemeManager.deleteCustom(this, seed); buildSwatches(); refreshChecks() }
                .setNegativeButton("Cancel", null).show()
            true
        }
        swatchViews += seed to col
        return col
    }

    private fun refreshChecks() {
        val applied = ThemeManager.seed(this)
        swatchViews.forEach { (seed, v) ->
            v.findViewWithTag<View>("check")?.isVisible = (seed or 0xFF000000.toInt()) == draft
            v.alpha = 1f
            v.contentDescription = if (seed == applied) "Current theme" else null
        }
    }

    private fun saveDialog() {
        val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS; hint = "Theme name"; setText("My ${ThemeManager.hex(draft)}") }
        val box = FrameLayout(this).apply { setPadding(dp(20), dp(10), dp(20), 0); addView(input) }
        MaterialAlertDialogBuilder(this).setTitle("Save this color").setView(box)
            .setPositiveButton("Save & apply") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { ThemeManager.hex(draft) }
                ThemeManager.saveCustom(this, name, draft)
                ThemeManager.setSeed(this, draft)
                Toast.makeText(this, "Saved \"$name\"", Toast.LENGTH_SHORT).show()
                recreate()
            }
            .setNegativeButton("Cancel", null).show()
    }
}
