package com.hark.velaplayer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.CountDownTimer
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import com.google.android.material.button.MaterialButton
import kotlin.math.hypot
import kotlin.math.min

private fun Context.dp(v: Float) = v * resources.displayMetrics.density
private fun Context.themeColor(attr: Int): Int {
    val tv = TypedValue(); theme.resolveAttribute(attr, tv, true)
    return if (tv.resourceId != 0) androidx.core.content.ContextCompat.getColor(this, tv.resourceId) else tv.data
}

/** 3×3 pattern lock drawn by hand. Reports the dot sequence ("0-4-8…") once the finger lifts. */
class VaultPatternView(context: Context) : View(context) {
    var onPattern: ((String) -> Unit)? = null
    private val seq = ArrayList<Int>()
    private var fx = -1f; private var fy = -1f
    private var error = false
    private val accent = ThemeManager.primary(context)
    private val idle = context.themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
    private val errColor = 0xFFE5534B.toInt()
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }

    init { contentDescription = "Pattern lock: draw your pattern across the 3 by 3 dots" }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val max = context.dp(280f).toInt()
        val w = MeasureSpec.getSize(widthMeasureSpec).let { if (it == 0) max else min(it, max) }
        val h = MeasureSpec.getSize(heightMeasureSpec).let { if (it == 0) max else it }
        val s = min(w, if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) w else h)
        setMeasuredDimension(s, s)
    }

    private fun cx(i: Int) = width / 6f * (2 * (i % 3) + 1)
    private fun cy(i: Int) = height / 6f * (2 * (i / 3) + 1)

    private fun hit(x: Float, y: Float): Int {
        val r = width / 7f
        for (i in 0 until 9) if (hypot(x - cx(i), y - cy(i)) < r) return i
        return -1
    }

    private fun add(i: Int) {
        if (i < 0 || i in seq) return
        // Passing straight over a dot (e.g. 0 → 2 crosses 1) includes it, like the system pattern lock.
        seq.lastOrNull()?.let { last ->
            val r1 = last / 3; val c1 = last % 3; val r2 = i / 3; val c2 = i % 3
            if ((r1 + r2) % 2 == 0 && (c1 + c2) % 2 == 0) {
                val mid = (r1 + r2) / 2 * 3 + (c1 + c2) / 2
                if (mid != last && mid != i && mid !in seq) seq += mid
            }
        }
        seq += i
        performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { error = false; seq.clear(); add(hit(e.x, e.y)); fx = e.x; fy = e.y; parent?.requestDisallowInterceptTouchEvent(true) }
            MotionEvent.ACTION_MOVE -> { add(hit(e.x, e.y)); fx = e.x; fy = e.y }
            MotionEvent.ACTION_UP -> {
                fx = -1f
                val s = seq.joinToString("-")
                invalidate()
                if (seq.isNotEmpty()) onPattern?.invoke(s)
                return true
            }
            MotionEvent.ACTION_CANCEL -> { fx = -1f; seq.clear() }
        }
        invalidate()
        return true
    }

    fun showError() { error = true; invalidate(); postDelayed({ clear() }, 900) }
    fun clear() { error = false; seq.clear(); fx = -1f; invalidate() }

    override fun onDraw(c: Canvas) {
        val col = if (error) errColor else accent
        line.color = col; line.alpha = 170; line.strokeWidth = context.dp(5f)
        for (k in 1 until seq.size) c.drawLine(cx(seq[k - 1]), cy(seq[k - 1]), cx(seq[k]), cy(seq[k]), line)
        if (fx >= 0 && seq.isNotEmpty()) c.drawLine(cx(seq.last()), cy(seq.last()), fx, fy, line)
        for (i in 0 until 9) {
            val on = i in seq
            dot.color = if (on) col else idle
            dot.alpha = if (on) 255 else 140
            c.drawCircle(cx(i), cy(i), context.dp(if (on) 9f else 6f), dot)
            if (on) { dot.alpha = 50; c.drawCircle(cx(i), cy(i), context.dp(22f), dot) }
        }
    }
}

/** Numeric keypad with masked dots. Calls [onPin] when the user confirms (✓) a PIN of 4–12 digits. */
class VaultPinPad(context: Context) : LinearLayout(context) {
    var onPin: ((String) -> Unit)? = null
    private val sb = StringBuilder()
    private val dots = TextView(context)
    private val keys = ArrayList<View>()

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        dots.apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.35f
            TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall)
            minHeight = context.dp(44f).toInt()
            contentDescription = "PIN"
        }
        addView(dots, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val grid = GridLayout(context).apply { columnCount = 3; rowCount = 4 }
        val labels = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "✓")
        val size = context.dp(72f).toInt()
        labels.forEach { l ->
            val btn = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = l
                textSize = 22f
                insetTop = 0; insetBottom = 0
                cornerRadius = size / 2
                minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
                setPadding(0, 0, 0, 0)
                contentDescription = when (l) { "⌫" -> "Delete"; "✓" -> "Confirm"; else -> l }
                setOnClickListener { press(l) }
                Motion.pressable(this, 0.88f) // v2.12: keys squash and spring back
                if (l == "⌫") setOnLongClickListener { sb.clear(); render(); true }
            }
            keys += btn
            grid.addView(btn, GridLayout.LayoutParams().apply {
                width = size; height = size
                val m = context.dp(8f).toInt(); setMargins(m, m, m, m)
            })
        }
        addView(grid, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(8f).toInt() })
        render()
    }

    private fun press(l: String) {
        when (l) {
            "⌫" -> if (sb.isNotEmpty()) sb.deleteCharAt(sb.length - 1)
            "✓" -> { if (sb.length >= 4) { val pin = sb.toString(); onPin?.invoke(pin) } else dots.announceForAccessibility("Enter at least 4 digits") ; return }
            else -> if (sb.length < 12) sb.append(l)
        }
        render()
    }

    private var shown = 0
    private fun render() {
        // v2.12: each new dot pops in with a small spring.
        if (sb.length > shown && Motion.enabled(context)) { dots.scaleX = 1.12f; dots.scaleY = 1.12f; dots.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(Motion.spring).start() }
        shown = sb.length
        dots.text = if (sb.isEmpty()) "" else "●".repeat(sb.length)
        dots.hint = "Enter PIN"
    }

    fun clear() { sb.clear(); render() }
    override fun setEnabled(enabled: Boolean) { super.setEnabled(enabled); keys.forEach { it.isEnabled = enabled } }
}

/**
 * The lock screen used by the vault and by Vault security: icon, title, message, PIN pad or pattern, error line,
 * optional biometric and secondary buttons. Handles the lockout countdown itself.
 */
class VaultLockPanel(context: Context) : LinearLayout(context) {
    private val icon = ImageView(context)
    private val title = TextView(context)
    private val message = TextView(context)
    private val error = TextView(context)
    private val inputBox = android.widget.FrameLayout(context)
    val bioButton = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
    val secondary = MaterialButton(context, null, com.google.android.material.R.attr.borderlessButtonStyle)
    private var pin: VaultPinPad? = null
    private var pattern: VaultPatternView? = null
    private var timer: CountDownTimer? = null

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val pad = context.dp(24f).toInt()
        setPadding(pad, pad, pad, pad)
        // v2.8: the lock content used to hug the top of the screen. Weighted spacers above and below share the
        // free height (40 % above, 60 % below), so the PIN pad / pattern sits a little lower and optically centred
        // on every screen size. The host ScrollView uses fillViewport, so this only applies when there is spare
        // room; on short screens or with the keyboard up the spacers collapse to 0 and the content scrolls.
        // Same panel for unlock, setup, confirm and change, so the position is identical everywhere.
        addView(android.widget.Space(context), LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.4f))
        icon.setImageResource(R.drawable.ic_lock)
        icon.imageTintList = android.content.res.ColorStateList.valueOf(ThemeManager.primary(context))
        // v2.12: the lock sits in a soft glass orb.
        icon.setBackgroundResource(R.drawable.bg_empty_orb)
        val ip = context.dp(18f).toInt(); icon.setPadding(ip, ip, ip, ip)
        addView(icon, LayoutParams(context.dp(72f).toInt(), context.dp(72f).toInt()))
        TextViewCompat.setTextAppearance(title, com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall)
        title.gravity = Gravity.CENTER
        addView(title, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(12f).toInt() })
        TextViewCompat.setTextAppearance(message, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
        message.gravity = Gravity.CENTER; message.alpha = 0.75f
        addView(message, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(6f).toInt() })
        TextViewCompat.setTextAppearance(error, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
        error.gravity = Gravity.CENTER; error.setTextColor(0xFFE5534B.toInt()); error.minHeight = context.dp(24f).toInt()
        error.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        addView(error, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(6f).toInt() })
        addView(inputBox, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(4f).toInt() })
        bioButton.text = "Use fingerprint"
        bioButton.setIconResource(R.drawable.ic_lock_open)
        bioButton.isVisible = false
        addView(bioButton, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(12f).toInt() })
        secondary.isVisible = false
        addView(secondary, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(4f).toInt() })
        addView(android.widget.Space(context), LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.6f))
    }

    private var entered = false
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // v2.8: each step (unlock, new, confirm, verify) eases in, so moving between steps reads as one flow.
        if (!entered) {
            entered = true; Motion.enter(this, fromY = context.dp(16f), duration = Motion.MEDIUM)
            // v2.12: blur-to-sharp + the orb settles from a little larger.
            Motion.blurIn(this, 14f, Motion.LONG)
            if (Motion.enabled(context)) { icon.scaleX = 1.25f; icon.scaleY = 1.25f; icon.alpha = 0f
                icon.animate().scaleX(1f).scaleY(1f).alpha(1f).setStartDelay(80).setDuration(Motion.XLONG).setInterpolator(Motion.springSoft).start() }
        }
    }

    fun setTexts(t: String, m: String?) { title.text = t; message.text = m ?: ""; message.isVisible = !m.isNullOrBlank() }
    fun setError(e: String?) { error.text = e ?: "" }

    /** Shows the PIN pad (method = PIN), the pattern (PATTERN) or no input (NONE). */
    fun showInput(method: String, onSecret: (String) -> Unit) {
        inputBox.removeAllViews(); pin = null; pattern = null
        when (method) {
            VaultAuth.PIN -> pin = VaultPinPad(context).also { it.onPin = onSecret; inputBox.addView(it) }
            VaultAuth.PATTERN -> pattern = VaultPatternView(context).also {
                it.onPattern = onSecret
                inputBox.addView(it, android.widget.FrameLayout.LayoutParams(context.dp(280f).toInt(), context.dp(280f).toInt()))
            }
        }
    }

    fun wrong(msg: String) { setError(msg); pin?.clear(); pattern?.showError(); shake() }
    fun clearInput() { pin?.clear(); pattern?.clear() }

    /** Blocks input and counts down; [then] runs when input is allowed again. */
    fun lockFor(ms: Long, then: () -> Unit = {}) {
        timer?.cancel()
        setInputEnabled(false); clearInput()
        timer = object : CountDownTimer(ms, 1000) {
            override fun onTick(left: Long) { setError("Too many attempts. Try again in ${(left + 999) / 1000} s") }
            override fun onFinish() { setError(null); setInputEnabled(true); then() }
        }.start()
    }

    fun setInputEnabled(on: Boolean) { pin?.isEnabled = on; pattern?.isEnabled = on; bioButton.isEnabled = on }

    private fun shake() {
        val d = context.dp(10f)
        inputBox.animate().cancel()
        inputBox.translationX = 0f
        android.animation.ObjectAnimator.ofFloat(inputBox, "translationX", 0f, -d, d, -d * 0.6f, d * 0.6f, 0f).setDuration(320).start()
    }

    override fun onDetachedFromWindow() { timer?.cancel(); super.onDetachedFromWindow() }
}

