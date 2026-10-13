package com.hark.velaplayer

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * v2.13 item options sheet (long-press / ⋮ on a video, folder, playlist, and Vault items).
 *
 * Midnight Glass: a floating level-3 glass card (inset from the screen edges, all corners rounded) over the
 * blurred screen (Android 12+ window blur from [R.style.VelaFloatingSheet]). A header with the item's thumbnail
 * (or an icon orb), title and subtitle, then action groups — each group a soft level-1 glass panel, separated by
 * spacing rather than dividers. Rows: accent icon chip + label, rounded ripple + press scale. Destructive actions
 * get a subtle error tint. Entrance: card floats in on [Motion.springSoft], rows stagger in. Reduced motion = no motion.
 *
 * Behaviour is the caller's: a tap dismisses the sheet and runs the action, exactly like the v1.4 sheet did.
 */
class ItemSheet(private val activity: Activity) {

    class Action(val label: String, val icon: Int, val danger: Boolean = false, val run: () -> Unit)

    /** Header visual: [bindThumb] fills a 16:9 thumbnail; otherwise [icon] is shown in a glass orb. */
    class Header(val title: String, val subtitle: String?, val icon: Int = R.drawable.ic_movie, val bindThumb: ((ImageView) -> Unit)? = null)

    private val c = activity
    private val d = c.resources.displayMetrics.density
    private fun dp(v: Float) = (v * d).toInt()
    private fun dp(v: Int) = (v * d).toInt()

    fun show(header: Header, groups: List<List<Action>>) {
        val sheet = BottomSheetDialog(c, R.style.VelaFloatingSheet)
        sheet.dismissWithAnimation = true
        val primary = ThemeManager.primary(c)
        val onSurface = ThemeManager.attrOr(c, com.google.android.material.R.attr.colorOnSurface, 0xFFFFFFFF.toInt())
        val error = ThemeManager.attrOr(c, com.google.android.material.R.attr.colorError, 0xFFFF6B6B.toInt())
        val motion = Motion.enabled(c)
        val screenH = c.resources.displayMetrics.heightPixels

        val card = object : LinearLayout(c) {
            val maxH = (screenH * 0.86f).toInt()
            override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST))
        }.apply {
            orientation = LinearLayout.VERTICAL
            background = Glass.floatingPanel(c, Tokens.RADIUS_XL)
            elevation = Tokens.ELEV_DIALOG * d
            setPadding(0, dp(8), 0, dp(10))
            isClickable = true
        }
        // grab handle
        card.addView(View(c).apply {
            background = GradientDrawable().apply { setColor((onSurface and 0x00FFFFFF) or 0x40000000); cornerRadius = dp(2).toFloat() }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(36), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(6) })

        // ---- header: thumbnail / icon orb + title + subtitle
        val head = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(14))
        }
        val visual: View = if (header.bindThumb != null) {
            ImageView(c).apply {
                background = Glass.panel(c, Tokens.RADIUS_S)
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, Tokens.RADIUS_S * d) }
                }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                header.bindThumb.invoke(this)
            }.also { head.addView(it, LinearLayout.LayoutParams(dp(92), dp(52)).apply { marginEnd = dp(14) }) }
        } else {
            FrameLayout(c).apply {
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    intArrayOf((primary and 0x00FFFFFF) or 0x40000000, (primary and 0x00FFFFFF) or 0x14000000)).apply {
                    shape = GradientDrawable.OVAL; setStroke(dp(1), (primary and 0x00FFFFFF) or 0x4D000000)
                }
                addView(ImageView(c).apply {
                    setImageResource(header.icon); imageTintList = ColorStateList.valueOf(primary)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
            }.also { head.addView(it, LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginEnd = dp(14) }) }
        }
        val texts = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(c).apply {
            text = header.title; maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
            if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        })
        if (!header.subtitle.isNullOrBlank()) texts.addView(TextView(c).apply {
            text = header.subtitle; alpha = 0.62f; maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE
            TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            setPadding(0, dp(2), 0, 0)
        })
        head.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(head)

        // ---- grouped rows
        val list = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(12), dp(2)) }
        val animated = ArrayList<View>()
        groups.filter { it.isNotEmpty() }.forEachIndexed { gi, group ->
            val panel = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                background = Glass.panel(c, Tokens.RADIUS_M)
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }
            group.forEach { a -> panel.addView(row(sheet, a, primary, error), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
            list.addView(panel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (gi > 0) topMargin = dp(Tokens.SPACE_S + 2)
            })
            for (i in 0 until panel.childCount) animated += panel.getChildAt(i)
        }
        card.addView(ScrollView(c).apply { isVerticalScrollBarEnabled = true; isFillViewport = false; addView(list) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // The card floats: inset from the sides and the bottom (the sheet container itself is transparent).
        val host = FrameLayout(c).apply { setPadding(dp(10), 0, dp(10), dp(10)); clipToPadding = false; clipChildren = false }
        val maxW = dp(560)
        host.addView(card, FrameLayout.LayoutParams(
            minOf(c.resources.displayMetrics.widthPixels - dp(20), maxW), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM))
        sheet.setContentView(host)
        sheet.behavior.skipCollapsed = true
        sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        // Vault (and anything else that hides screenshots) keeps FLAG_SECURE on the sheet window too.
        if (activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            sheet.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        // ---- entrance: float-in on a soft spring + staggered rows
        if (motion) {
            card.alpha = 0f; card.translationY = dp(56).toFloat(); card.scaleX = 0.94f; card.scaleY = 0.94f
            card.pivotY = dp(400).toFloat()
            card.post { card.pivotX = card.width / 2f; card.pivotY = card.height.toFloat() }
            card.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setStartDelay(16).setDuration(440).setInterpolator(Motion.springSoft).start()
            head.alpha = 0f; head.translationY = dp(8).toFloat()
            head.animate().alpha(1f).translationY(0f).setStartDelay(70).setDuration(280).setInterpolator(Motion.emphasized).start()
            animated.take(16).forEachIndexed { i, r ->
                r.alpha = 0f; r.translationY = dp(12).toFloat()
                r.animate().alpha(1f).translationY(0f).setStartDelay(100L + i * Motion.STAGGER).setDuration(280).setInterpolator(Motion.emphasized).start()
            }
        }
        sheet.show()
    }

    private fun row(sheet: BottomSheetDialog, a: Action, primary: Int, error: Int): View {
        val tint = if (a.danger) error else primary
        val r = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            setPadding(dp(10), dp(6), dp(14), dp(6))
            val mask = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(-1) }
            val fill = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(if (a.danger) (error and 0x00FFFFFF) or 0x0F000000 else 0) }
            background = RippleDrawable(ColorStateList.valueOf((tint and 0x00FFFFFF) or 0x2E000000), fill, mask)
            contentDescription = a.label
            isFocusable = true
        }
        r.addView(FrameLayout(c).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(11).toFloat()
                setColor((tint and 0x00FFFFFF) or (if (a.danger) 0x24000000 else 0x1F000000))
                setStroke(dp(1), (tint and 0x00FFFFFF) or 0x29000000)
            }
            addView(ImageView(c).apply {
                setImageResource(a.icon); imageTintList = ColorStateList.valueOf(tint)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
        }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(14) })
        r.addView(TextView(c).apply {
            text = a.label; maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
            if (a.danger) setTextColor(error)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        Motion.pressable(r, 0.97f)
        r.setOnClickListener { sheet.dismiss(); a.run() }
        return r
    }
}
