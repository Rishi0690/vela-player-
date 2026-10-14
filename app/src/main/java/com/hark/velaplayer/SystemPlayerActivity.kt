package com.hark.velaplayer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * v2.19.3 last-resort engine for LOCAL files ExoPlayer can't open (container/IO errors after the repair retry, or
 * decoder errors after both decoder kinds failed): android.media.MediaPlayer on a SurfaceView, the same platform
 * stack the system Gallery uses. Fully separate from ExoPlayer/PlayerActivity: PlayerActivity only starts it from
 * its final error path (`trySystemEngine`) and reads the result. Basic controls only: play/pause, seek bar, time,
 * back; tap toggles the controls. Results: [RESULT_COMPLETED] (played to the end), [RESULT_FAILED] (+ [EXTRA_ERROR];
 * PlayerActivity then shows/skips the original error as before), RESULT_CANCELED (user went back).
 */
class SystemPlayerActivity : Activity(), SurfaceHolder.Callback {
    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_SECURE = "secure"
        const val EXTRA_ERROR = "error"
        const val EXTRA_POSITION = "position"
        const val RESULT_COMPLETED = Activity.RESULT_FIRST_USER
        const val RESULT_FAILED = Activity.RESULT_FIRST_USER + 1

        fun intent(c: Context, uri: Uri, title: String, secure: Boolean): Intent =
            Intent(c, SystemPlayerActivity::class.java).setData(uri)
                .putExtra(EXTRA_TITLE, title).putExtra(EXTRA_SECURE, secure)
    }

    private var mp: MediaPlayer? = null
    private var prepared = false
    private var resumePos = 0
    private var wasPlaying = true
    private var userSeeking = false
    private var surface: SurfaceHolder? = null
    private lateinit var surfaceView: SurfaceView
    private lateinit var box: FrameLayout
    private lateinit var controls: LinearLayout
    private lateinit var topBar: LinearLayout
    private lateinit var playBtn: ImageButton
    private lateinit var seek: SeekBar
    private lateinit var time: TextView
    private lateinit var chip: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { updateProgress(); handler.postDelayed(this, 500) }
    }
    private val hideControls = Runnable { setControlsVisible(false) }
    private val hideChip = Runnable { chip.animate().alpha(0f).setDuration(300).start() }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra(EXTRA_SECURE, false)) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        resumePos = savedInstanceState?.getInt(EXTRA_POSITION) ?: 0
        buildUi()
        setContentView(box)
        open()
    }

    private fun buildUi() {
        box = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        surfaceView = SurfaceView(this)
        surfaceView.holder.addCallback(this)
        box.addView(surfaceView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        box.setOnClickListener { setControlsVisible(controls.visibility != View.VISIBLE) }

        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(16), dp(8)); setBackgroundColor(0x88000000.toInt())
        }
        val back = ImageButton(this).apply {
            setImageResource(R.drawable.ic_arrow_back); setBackgroundColor(Color.TRANSPARENT); setColorFilter(Color.WHITE)
            contentDescription = "Back"; setOnClickListener { finish() }
        }
        topBar.addView(back, LinearLayout.LayoutParams(dp(48), dp(48)))
        topBar.addView(TextView(this).apply {
            text = intent.getStringExtra(EXTRA_TITLE) ?: ""; setTextColor(Color.WHITE); textSize = 16f; maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(dp(8), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(topBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(16), dp(12)); setBackgroundColor(0x88000000.toInt())
        }
        playBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_pause); setBackgroundColor(Color.TRANSPARENT); setColorFilter(Color.WHITE)
            contentDescription = "Play/pause"; setOnClickListener { togglePlay() }
        }
        controls.addView(playBtn, LinearLayout.LayoutParams(dp(48), dp(48)))
        seek = SeekBar(this).apply {
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) time.text = label(p) }
                override fun onStartTrackingTouch(s: SeekBar) { userSeeking = true; handler.removeCallbacks(hideControls) }
                override fun onStopTrackingTouch(s: SeekBar) {
                    userSeeking = false
                    if (prepared) mp?.seekTo(s.progress)
                    scheduleHide()
                }
            })
        }
        controls.addView(seek, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        time = TextView(this).apply { setTextColor(Color.WHITE); textSize = 13f; text = "0:00 / --:--" }
        controls.addView(time, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        box.addView(controls, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        chip = TextView(this).apply {
            text = "Playing with system engine"; setTextColor(Color.WHITE); textSize = 13f
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(0xCC202024.toInt()) }
        }
        box.addView(chip, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(84) })
        handler.postDelayed(hideChip, 4_000)

        ViewCompat.setOnApplyWindowInsetsListener(box) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            topBar.setPadding(dp(8) + bars.left, dp(8) + bars.top, dp(16) + bars.right, dp(8))
            controls.setPadding(dp(12) + bars.left, dp(8), dp(16) + bars.right, dp(12) + bars.bottom)
            insets
        }
        box.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitVideo() }
    }

    private fun open() {
        val uri = intent.data ?: return fail("no uri")
        val p = MediaPlayer()
        mp = p
        try {
            p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
            p.setDataSource(this, uri)
            p.setOnPreparedListener {
                prepared = true
                seek.max = it.duration.coerceAtLeast(0)
                surface?.let { h -> it.setDisplay(h) }
                if (resumePos > 0) it.seekTo(resumePos)
                if (wasPlaying) it.start()
                updatePlayIcon(); fitVideo(); handler.post(tick); scheduleHide()
            }
            p.setOnVideoSizeChangedListener { _, _, _ -> fitVideo() }
            p.setOnCompletionListener { setResult(RESULT_COMPLETED); finish() }
            p.setOnErrorListener { _, what, extra -> fail("MediaPlayer error what=$what extra=$extra"); true }
            surface?.let { p.setDisplay(it) }
            p.prepareAsync()
        } catch (t: Throwable) {
            fail("${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun fail(msg: String) {
        setResult(RESULT_FAILED, Intent().putExtra(EXTRA_ERROR, msg))
        finish()
    }

    private fun fitVideo() {
        val p = mp ?: return
        if (!prepared) return
        val vw = p.videoWidth; val vh = p.videoHeight
        val bw = box.width; val bh = box.height
        if (vw <= 0 || vh <= 0 || bw <= 0 || bh <= 0) return
        val scale = minOf(bw.toFloat() / vw, bh.toFloat() / vh)
        val lp = surfaceView.layoutParams as FrameLayout.LayoutParams
        val w = (vw * scale).toInt(); val h = (vh * scale).toInt()
        if (lp.width != w || lp.height != h) { lp.width = w; lp.height = h; lp.gravity = Gravity.CENTER; surfaceView.layoutParams = lp }
    }

    private fun togglePlay() {
        val p = mp ?: return
        if (!prepared) return
        if (p.isPlaying) p.pause() else p.start()
        updatePlayIcon(); scheduleHide()
    }

    private fun updatePlayIcon() {
        playBtn.setImageResource(if (mp?.let { prepared && it.isPlaying } == true) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun updateProgress() {
        val p = mp ?: return
        if (!prepared || userSeeking) return
        val pos = try { p.currentPosition } catch (_: IllegalStateException) { return }
        seek.progress = pos
        time.text = label(pos)
    }

    private fun label(pos: Int) = "${Fmt.time(pos.toLong())} / ${Fmt.time(seek.max.toLong())}"

    private fun setControlsVisible(on: Boolean) {
        controls.visibility = if (on) View.VISIBLE else View.GONE
        topBar.visibility = controls.visibility
        if (on) scheduleHide()
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideControls)
        if (mp?.let { prepared && it.isPlaying } == true) handler.postDelayed(hideControls, 3_500)
    }

    override fun surfaceCreated(holder: SurfaceHolder) { surface = holder; mp?.setDisplay(holder) }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
    override fun surfaceDestroyed(holder: SurfaceHolder) { surface = null; try { mp?.setDisplay(null) } catch (_: Throwable) {} }

    override fun onPause() {
        super.onPause()
        val p = mp
        if (p != null && prepared) {
            wasPlaying = p.isPlaying
            resumePos = p.currentPosition
            if (p.isPlaying) p.pause()
            updatePlayIcon()
        }
    }

    override fun onResume() {
        super.onResume()
        val p = mp
        if (p != null && prepared && wasPlaying && !p.isPlaying) { p.start(); updatePlayIcon() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(EXTRA_POSITION, mp?.takeIf { prepared }?.currentPosition ?: resumePos)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try { mp?.release() } catch (_: Throwable) {}
        mp = null
        super.onDestroy()
    }
}
