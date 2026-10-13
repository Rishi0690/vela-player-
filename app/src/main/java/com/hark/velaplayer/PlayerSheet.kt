package com.hark.velaplayer

import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import com.google.android.material.button.MaterialButton

/**
 * The player's option panels (subtitles, audio, aspect ratio, player options) in one consistent design.
 * Landscape: a floating card on the right, so most of the video stays visible.
 * Portrait: a sheet from the bottom. Long names wrap instead of being cut off.
 */
class PlayerSheet(private val host: FrameLayout, private val onClosed: () -> Unit) {
    class Option(
        val title: String,
        val subtitle: String? = null,
        val selected: Boolean = false,
        val icon: Int = 0,
        /** Shown at the end of the row, e.g. "On" / "Off" for toggles. */
        val trailing: String? = null,
        val keepOpen: Boolean = false,
        val onClick: () -> Unit
    )
    class Action(val label: String, val icon: Int, val onClick: () -> Unit)

    private val c = host.context
    private val d = c.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private var scrim: View? = null
    private var panel: LinearLayout? = null
    val isOpen get() = panel != null

    /** [radio] = rows show a radio indicator (single choice lists). */
    fun show(title: String, options: List<Option>, actions: List<Action> = emptyList(), radio: Boolean = true, footer: String? = null) {
        dismiss(animate = false, notify = false)
        val primary = ThemeManager.primary(c)
        val landscape = c.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val hostW = if (host.width > 0) host.width else c.resources.displayMetrics.widthPixels
        val hostH = if (host.height > 0) host.height else c.resources.displayMetrics.heightPixels

        val s = View(c).apply {
            setBackgroundColor(0x55000000)
            alpha = 0f
            setOnClickListener { dismiss() }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        host.addView(s, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val p = object : LinearLayout(c) {
            val maxH = if (landscape) hostH - dp(32) else (hostH * 0.62f).toInt()
            override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST))
        }.apply {
            orientation = LinearLayout.VERTICAL
            background = Glass.playerPanel(c, 24f) // v2.11 dark glass, no blur over video
            elevation = dp(8).toFloat()
            isClickable = true
            setPadding(0, dp(if (landscape) 14 else 8), 0, dp(12))
        }
        if (!landscape) p.addView(View(c).apply {
            background = GradientDrawable().apply { setColor(0x4DFFFFFF); cornerRadius = dp(2).toFloat() }
        }, LinearLayout.LayoutParams(dp(36), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(8) })

        p.addView(TextView(c).apply {
            text = title
            TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_TitleLarge)
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(20), dp(4), dp(20), dp(10))
            accessibilityHeading()
        })

        if (actions.isNotEmpty()) {
            val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(16), 0, dp(16), dp(8)) }
            actions.forEach { a ->
                row.addView(MaterialButton(c, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = a.label
                    setIconResource(a.icon)
                    iconTint = ColorStateList.valueOf(0xFFFFFFFF.toInt())
                    setTextColor(0xFFFFFFFF.toInt())
                    strokeColor = ColorStateList.valueOf(0x55FFFFFF)
                    isAllCaps = false
                    setOnClickListener { dismiss(); a.onClick() }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)).apply { marginEnd = dp(8) })
            }
            p.addView(HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; addView(row) })
        }

        val list = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, dp(8), 0) }
        val rows = ArrayList<View>()
        options.forEachIndexed { i, o ->
            val row = LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(56)
                setPadding(dp(14), dp(8), dp(14), dp(8))
                background = rowBg(o.selected, primary)
                isSelected = o.selected
                contentDescription = listOfNotNull(o.title, o.subtitle, o.trailing, if (o.selected) "selected" else null).joinToString(", ")
            }
            val iconRes = if (o.icon != 0) o.icon else if (radio) (if (o.selected) R.drawable.ic_radio_on else R.drawable.ic_radio_off) else 0
            if (iconRes != 0) row.addView(ImageView(c).apply {
                setImageResource(iconRes)
                imageTintList = ColorStateList.valueOf(if (o.selected) primary else 0xB3FFFFFF.toInt())
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                tag = "icon"
            }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(16) })
            val texts = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(c).apply {
                text = o.title; maxLines = 3; ellipsize = TextUtils.TruncateAt.END
                TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
                setTextColor(if (o.selected) primary else 0xFFFFFFFF.toInt())
                tag = "title"
            })
            if (!o.subtitle.isNullOrBlank()) texts.addView(TextView(c).apply {
                text = o.subtitle; maxLines = 2; ellipsize = TextUtils.TruncateAt.END; alpha = 0.7f
                TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                setTextColor(0xFFFFFFFF.toInt())
            })
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (o.trailing != null) row.addView(TextView(c).apply {
                text = o.trailing; alpha = 0.8f
                TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
                setTextColor(if (o.trailing == "On") primary else 0xFFFFFFFF.toInt())
                setPadding(dp(12), 0, 0, 0)
            })
            row.setOnClickListener {
                if (radio) rows.forEachIndexed { j, r -> markRow(r, j == i, primary) }
                Motion.bounce(row.findViewWithTag<View>("icon") ?: row)
                if (o.keepOpen) o.onClick()
                else row.postDelayed({ dismiss(); o.onClick() }, if (radio) 160L else 60L)
            }
            rows += row
            list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(2) })
        }
        if (footer != null) list.addView(TextView(c).apply {
            text = footer; alpha = 0.6f
            TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            setTextColor(0xFFFFFFFF.toInt()); setPadding(dp(14), dp(8), dp(14), 0)
        })
        val scroll = ScrollView(c).apply { isVerticalScrollBarEnabled = true; addView(list); isFillViewport = false }
        p.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val lp = if (landscape) FrameLayout.LayoutParams(minOf(dp(400), (hostW * 0.46f).toInt()), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(16) }
        else FrameLayout.LayoutParams(minOf(hostW - dp(16), dp(560)), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(8) }
        host.addView(p, lp)
        scrim = s; panel = p

        s.animate().alpha(1f).setDuration(200).start()
        p.alpha = 0f
        if (landscape) p.translationX = dp(48).toFloat() else p.translationY = dp(64).toFloat()
        p.animate().alpha(1f).translationX(0f).translationY(0f).setDuration(300).setInterpolator(Motion.emphasized).start()
        // Bring the selected row into view in long lists.
        val sel = rows.indexOfFirst { it.isSelected }
        if (sel > 3) scroll.post { scroll.smoothScrollTo(0, (rows[sel].top - dp(24)).coerceAtLeast(0)) }
    }

    private fun rowBg(selected: Boolean, primary: Int) = GradientDrawable().apply {
        cornerRadius = dp(14).toFloat()
        setColor(if (selected) (primary and 0x00FFFFFF) or 0x33000000 else 0x00000000)
    }.let { bg ->
        android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), bg, GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(-1) })
    }

    private fun markRow(row: View, selected: Boolean, primary: Int) {
        row.isSelected = selected
        row.background = rowBg(selected, primary)
        row.findViewWithTag<ImageView>("icon")?.let {
            it.setImageResource(if (selected) R.drawable.ic_radio_on else R.drawable.ic_radio_off)
            it.imageTintList = ColorStateList.valueOf(if (selected) primary else 0xB3FFFFFF.toInt())
        }
        row.findViewWithTag<TextView>("title")?.setTextColor(if (selected) primary else 0xFFFFFFFF.toInt())
    }

    fun dismiss(animate: Boolean = true, notify: Boolean = true) {
        val p = panel ?: return
        val s = scrim
        panel = null; scrim = null
        val landscape = c.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (animate) {
            s?.animate()?.alpha(0f)?.setDuration(180)?.withEndAction { host.removeView(s) }?.start()
            p.animate().alpha(0f).translationX(if (landscape) dp(40).toFloat() else 0f).translationY(if (landscape) 0f else dp(48).toFloat())
                .setDuration(200).setInterpolator(Motion.emphasizedAccel).withEndAction { host.removeView(p) }.start()
        } else { host.removeView(p); s?.let { host.removeView(it) } }
        if (notify) onClosed()
    }

    private fun TextView.accessibilityHeading() { if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true }
}
