package com.hark.velaplayer

import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Rational
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.HtmlCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.common.util.concurrent.ListenableFuture
import com.hark.velaplayer.databinding.ActivityPlayerBinding
import com.hark.velaplayer.databinding.DialogSubtitleStyleBinding
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URIS = "uris"
        const val EXTRA_TITLES = "titles"
        const val EXTRA_INDEX = "index"
        private val SPEEDS = floatArrayOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 4f)

        fun formatSpeed(s: Float): String {
            val r = (s * 100).roundToInt() / 100f
            return (if (r == r.toInt().toFloat()) r.toInt().toString() else r.toString()) + "x"
        }
    }

    private lateinit var b: ActivityPlayerBinding
    private lateinit var player: ExoPlayer
    private lateinit var audioManager: AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private var controlsVisible = true
    private var locked = false
    private var fullscreen = true
    private var aspect = AspectMode.FIT
    private var seekIntervalMs = 10_000L
    private var userSeeking = false
    private var userRotated = false
    private var isLocalMedia = false
    private var playerReady = false

    // External (SRT/VTT) subtitles rendered by us so the delay can be shifted.
    private var externalSubs: List<SubtitleParser.Line>? = null
    private var externalName: String? = null
    private var externalActive = false
    private var subtitleDelayMs = 0L
    private var shownExternal: List<SubtitleParser.Line> = emptyList()

    private var videoDecoder: String? = null
    private var audioDecoder: String? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null

    // Seek preview (local files only), accessed on the io thread.
    private var retriever: MediaMetadataRetriever? = null
    private var retrieverUri: Uri? = null
    @Volatile private var previewTarget = -1L
    @Volatile private var previewBusy = false

    private enum class Drag { NONE, IGNORE, SEEK, BRIGHTNESS, VOLUME, PAN }
    private var drag = Drag.NONE
    private var scaling = false
    private var seekStart = 0L
    private var seekTarget = 0L
    private var volumeLevel = 0f
    private var brightness = 0.5f
    private var touchSlop = 0
    private var lastTapSide = 0
    private var lastTapTime = 0L
    private var tapAccum = 0L
    private lateinit var gestures: GestureDetector
    private lateinit var scaleGestures: ScaleGestureDetector

    private val hideControlsRunnable = Runnable { setControlsVisible(false) }
    private val hideIndicatorRunnable = Runnable { b.indicator.isVisible = false }
    private val hideTapRunnable = Runnable { b.tapLeft.isVisible = false; b.tapRight.isVisible = false; tapAccum = 0L }
    private val hideUnlockRunnable = Runnable { b.btnUnlock.isVisible = false }
    private val ticker = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, if (externalActive) 100L else 250L)
        }
    }

    private val pickSubtitle = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadExternalSubtitle(uri)
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(b.root)
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        touchSlop = ViewConfiguration.get(this).scaledTouchSlop

        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            b.videoFrame.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            b.controls.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        setFullscreen(true)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (locked) showUnlockButton() else finish() }
        })

        seekIntervalMs = Prefs.seekInterval(this)
        aspect = Prefs.defaultAspect(this)
        brightness = currentBrightness()

        player = PlayerHolder.create(this)
        playerReady = true
        player.setVideoTextureView(b.videoLayout.textureView)
        player.addListener(playerListener)
        player.addAnalyticsListener(analyticsListener)
        b.videoLayout.mode = aspect
        b.btnAspectLabel.text = aspect.short
        applySubtitleStyle()
        setupControls()
        setupGestures()
        if (!pipSupported()) b.btnPip.isVisible = false

        if (!loadQueue(intent)) {
            Toast.makeText(this, "Nothing to play", Toast.LENGTH_SHORT).show()
            finish(); return
        }
        applyOrientation(null)
        handler.post(ticker)
        setControlsVisible(true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        savePosition()
        userRotated = false
        loadQueue(intent)
    }

    override fun onStart() {
        super.onStart()
        if (Prefs.backgroundAudio(this)) connectSession()
    }

    override fun onStop() {
        super.onStop()
        savePosition()
        val keepPlaying = Prefs.backgroundAudio(this) && player.isPlaying && !isFinishing
        if (!keepPlaying) player.pause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (playerReady) {
            savePosition()
            disconnectSession()
            player.removeListener(playerListener)
            player.removeAnalyticsListener(analyticsListener)
            PlayerHolder.release()
        }
        io.execute { retriever?.release(); retriever = null }
        io.shutdown()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && fullscreen) setFullscreen(true)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        b.videoLayout.resetZoom(false)
        b.videoLayout.requestLayout()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Prefs.autoPip(this) && player.isPlaying && pipSupported()) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            setControlsVisible(false)
            b.indicator.isVisible = false
            b.btnUnlock.isVisible = false
        }
        b.videoLayout.resetZoom(false)
    }

    // ------------------------------------------------------------------ queue / media

    private fun loadQueue(intent: Intent): Boolean {
        val uris = intent.getStringArrayListExtra(EXTRA_URIS)
        val titles = intent.getStringArrayListExtra(EXTRA_TITLES)
        val entries = ArrayList<Entry>()
        if (!uris.isNullOrEmpty()) {
            uris.forEachIndexed { i, u -> entries += Entry(u, titles?.getOrNull(i) ?: (Uri.parse(u).lastPathSegment ?: "Video")) }
        } else {
            intent.data?.let { entries += Entry(it.toString(), displayName(it)) }
        }
        if (entries.isEmpty()) return false
        val start = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, entries.lastIndex)
        val startPos = if (Prefs.resume(this)) Store.position(this, entries[start].uri) else 0L
        externalSubs = null; externalActive = false; shownExternal = emptyList()
        b.errorPanel.isVisible = false
        player.setMediaItems(entries.map { buildItem(it) }, start, startPos)
        player.setPlaybackSpeed(Prefs.speed(this))
        player.pauseAtEndOfMediaItems = !Prefs.autoplayNext(this)
        player.prepare()
        player.playWhenReady = true
        return true
    }

    private fun buildItem(e: Entry): MediaItem {
        val lower = e.uri.lowercase(Locale.ROOT)
        val builder = MediaItem.Builder().setUri(e.uri).setMediaId(e.uri)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(e.title).build())
        when {
            lower.contains(".m3u8") -> builder.setMimeType(MimeTypes.APPLICATION_M3U8)
            lower.contains(".mpd") -> builder.setMimeType(MimeTypes.APPLICATION_MPD)
        }
        return builder.build()
    }

    private fun displayName(uri: Uri): String {
        if (uri.scheme == "content") {
            try {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0)?.let { return it }
                }
            } catch (_: Exception) { }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "Video"
    }

    private fun savePosition() {
        if (!playerReady) return
        val item = player.currentMediaItem ?: return
        val dur = player.duration
        if (!player.isCurrentMediaItemSeekable || player.isCurrentMediaItemLive) return
        Store.savePosition(this, item.mediaId, item.mediaMetadata.title?.toString() ?: "Video", player.currentPosition,
            if (dur == C.TIME_UNSET) 0L else dur)
    }

    // ------------------------------------------------------------------ player callbacks

    private val playerListener = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                b.videoLayout.setVideoSize(videoSize.width, videoSize.height, videoSize.pixelWidthHeightRatio)
                applyOrientation(videoSize)
                updatePipParams()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            b.loading.isVisible = playbackState == Player.STATE_BUFFERING
            if (playbackState == Player.STATE_READY) b.errorPanel.isVisible = false
            if (playbackState == Player.STATE_ENDED) {
                player.currentMediaItem?.let { Store.clearPosition(this@PlayerActivity, it.mediaId) }
                setControlsVisible(true)
            }
            updatePlayButton(); updateProgress()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            updatePlayButton()
            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) setControlsVisible(true)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePlayButton()
            if (isPlaying && Prefs.keepScreenOn(this@PlayerActivity)) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (isPlaying) scheduleHide() else { handler.removeCallbacks(hideControlsRunnable); savePosition() }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            b.titleText.text = mediaItem?.mediaMetadata?.title ?: ""
            val scheme = mediaItem?.localConfiguration?.uri?.scheme
            isLocalMedia = scheme == "content" || scheme == "file"
            externalSubs = null; externalActive = false; externalName = null; shownExternal = emptyList()
            b.subtitleView.setCues(emptyList())
            videoDecoder = null; audioDecoder = null
            io.execute { retriever?.release(); retriever = null; retrieverUri = null }
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED && mediaItem != null && Prefs.resume(this@PlayerActivity)) {
                val p = Store.position(this@PlayerActivity, mediaItem.mediaId)
                if (p > 0) player.seekTo(p)
            }
            updateNavButtons()
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                oldPosition.mediaItem?.let { Store.clearPosition(this@PlayerActivity, it.mediaId) }
            }
        }

        override fun onPlayerError(error: PlaybackException) { showError(error) }

        override fun onCues(cueGroup: CueGroup) { if (!externalActive) b.subtitleView.setCues(cueGroup.cues) }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            b.btnSpeed.text = formatSpeed(playbackParameters.speed)
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) { updateNavButtons(); updateProgress() }
    }

    private val analyticsListener = object : AnalyticsListener {
        override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
            videoDecoder = decoderName
        }
        override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
            audioDecoder = decoderName
        }
    }

    private fun showError(e: PlaybackException) {
        val msg = when (e.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Network problem. Check your connection and retry."
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> {
                val code = (e.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                "The server refused this stream" + (if (code != null) " (HTTP $code)." else ".")
            }
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "File not found. It may have been moved or deleted."
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> "No permission to read this file."
            PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED -> "Plain HTTP is blocked for this address."
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> "This file type isn't supported."
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> "The file or stream looks damaged."
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> "This stream format isn't supported."
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ->
                "This device can't decode this video's codec. Try switching the decoder in Settings."
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED -> "Couldn't start audio output."
            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> "Fell behind the live stream."
            PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR,
            PlaybackException.ERROR_CODE_DRM_UNSPECIFIED -> "This video is DRM-protected and can't be played here."
            else -> "Playback error (${e.errorCodeName})."
        }
        b.errorText.text = msg
        b.errorPanel.isVisible = true
        b.loading.isVisible = false
        setControlsVisible(true)
    }

    private fun retry() {
        b.errorPanel.isVisible = false
        if (player.playerError?.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) player.seekToDefaultPosition()
        player.prepare()
        player.play()
    }

    // ------------------------------------------------------------------ controls

    private fun setupControls() {
        b.btnBack.setOnClickListener { finish() }
        b.btnPlay.setOnClickListener { togglePlayPause(); scheduleHide() }
        b.btnRewind.setOnClickListener { seekBy(-seekIntervalMs); scheduleHide() }
        b.btnForward.setOnClickListener { seekBy(seekIntervalMs); scheduleHide() }
        b.btnPrev.setOnClickListener { savePosition(); player.seekToPreviousMediaItem(); scheduleHide() }
        b.btnNext.setOnClickListener { savePosition(); player.seekToNextMediaItem(); scheduleHide() }
        b.btnSpeed.setOnClickListener { showSpeedDialog() }
        b.btnAspect.setOnClickListener { showAspectDialog() }
        b.btnAspect.setOnLongClickListener { cycleAspect(); true }
        b.btnAspectLabel.setOnClickListener { cycleAspect() }
        b.btnAspectLabel.setOnLongClickListener { showAspectDialog(); true }
        b.btnSubtitle.setOnClickListener { showSubtitleDialog() }
        b.btnAudio.setOnClickListener { showAudioDialog() }
        b.btnMore.setOnClickListener { showMoreMenu(it) }
        b.btnLock.setOnClickListener { lockControls() }
        b.btnUnlock.setOnClickListener { unlockControls() }
        b.btnRotate.setOnClickListener { rotateScreen(); scheduleHide() }
        b.btnPip.setOnClickListener { enterPip() }
        b.btnFullscreen.setOnClickListener { setFullscreen(!fullscreen); scheduleHide() }
        b.btnRetry.setOnClickListener { retry() }
        b.btnErrorClose.setOnClickListener { finish() }
        b.btnRewind.contentDescription = "Rewind ${seekIntervalMs / 1000} seconds"
        b.btnForward.contentDescription = "Forward ${seekIntervalMs / 1000} seconds"

        b.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    b.positionText.text = Fmt.time(progress.toLong())
                    showSeekPreview(progress.toLong())
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {
                userSeeking = true
                handler.removeCallbacks(hideControlsRunnable)
                showSeekPreview(sb.progress.toLong())
            }
            override fun onStopTrackingTouch(sb: SeekBar) {
                userSeeking = false
                player.seekTo(sb.progress.toLong())
                b.seekPreview.isVisible = false
                scheduleHide()
            }
        })
    }

    private fun togglePlayPause() {
        when {
            player.playbackState == Player.STATE_ENDED -> { player.seekTo(player.currentMediaItemIndex, 0); player.play() }
            player.playbackState == Player.STATE_IDLE -> { b.errorPanel.isVisible = false; player.prepare(); player.play() }
            player.playWhenReady -> player.pause()
            else -> player.play()
        }
    }

    private fun seekBy(delta: Long) {
        if (!player.isCurrentMediaItemSeekable) return
        val dur = player.duration
        var t = player.currentPosition + delta
        if (t < 0) t = 0
        if (dur != C.TIME_UNSET && t > dur) t = dur
        player.seekTo(t)
        updateProgress()
    }

    private fun updatePlayButton() {
        val showPause = player.playWhenReady && player.playbackState != Player.STATE_ENDED && player.playbackState != Player.STATE_IDLE
        b.btnPlay.setImageResource(if (showPause) R.drawable.ic_pause else R.drawable.ic_play)
        b.btnPlay.contentDescription = if (showPause) "Pause" else "Play"
    }

    private fun updateNavButtons() {
        val multi = player.mediaItemCount > 1
        b.btnPrev.isVisible = multi
        b.btnNext.isVisible = multi
        b.btnPrev.isEnabled = player.hasPreviousMediaItem()
        b.btnNext.isEnabled = player.hasNextMediaItem()
        b.btnPrev.alpha = if (b.btnPrev.isEnabled) 1f else 0.35f
        b.btnNext.alpha = if (b.btnNext.isEnabled) 1f else 0.35f
    }

    private fun updateProgress() {
        if (userSeeking) return
        val dur = player.duration
        val pos = player.currentPosition
        if (dur != C.TIME_UNSET && dur > 0) {
            b.seekBar.isEnabled = player.isCurrentMediaItemSeekable
            b.seekBar.max = dur.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            b.seekBar.progress = pos.toInt()
            b.seekBar.secondaryProgress = player.bufferedPosition.toInt()
            b.durationText.text = Fmt.time(dur)
        } else {
            b.seekBar.isEnabled = false
            b.seekBar.max = 1
            b.seekBar.progress = 0
            b.durationText.text = if (player.isCurrentMediaItemLive) "LIVE" else "--:--"
        }
        b.positionText.text = Fmt.time(pos)
    }

    private fun tick() {
        if (controlsVisible) updateProgress()
        if (externalActive) renderExternalSubs()
    }

    private fun setControlsVisible(visible: Boolean) {
        if (visible && locked) return
        if (visible && Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode) return
        controlsVisible = visible
        b.controls.animate().cancel()
        if (visible) {
            b.controls.isVisible = true
            b.controls.animate().alpha(1f).setDuration(180).start()
            updateProgress(); updatePlayButton(); updateNavButtons()
            scheduleHide()
        } else {
            handler.removeCallbacks(hideControlsRunnable)
            b.seekPreview.isVisible = false
            b.controls.animate().alpha(0f).setDuration(180).withEndAction {
                if (!controlsVisible) b.controls.isVisible = false
            }.start()
        }
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideControlsRunnable)
        if (controlsVisible && player.isPlaying && !userSeeking) handler.postDelayed(hideControlsRunnable, 3500)
    }

    private fun lockControls() {
        setControlsVisible(false)
        locked = true
        showUnlockButton()
        Toast.makeText(this, "Screen locked. Tap the lock to unlock.", Toast.LENGTH_SHORT).show()
    }

    private fun showUnlockButton() {
        b.btnUnlock.isVisible = true
        handler.removeCallbacks(hideUnlockRunnable)
        handler.postDelayed(hideUnlockRunnable, 2500)
    }

    private fun unlockControls() {
        locked = false
        b.btnUnlock.isVisible = false
        setControlsVisible(true)
    }

    private fun setFullscreen(on: Boolean) {
        fullscreen = on
        val c = WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
        }
        b.btnFullscreen.setImageResource(if (on) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen)
    }

    private fun applyOrientation(vs: VideoSize?) {
        if (userRotated) return
        val pref = Prefs.orientation(this)
        requestedOrientation = when {
            pref == "landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            pref == "portrait" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            pref == "sensor" -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            vs != null -> orientationFor(vs)
            else -> return
        }
    }

    private fun orientationFor(vs: VideoSize): Int =
        if (vs.width * vs.pixelWidthHeightRatio >= vs.height) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT

    private fun rotateScreen() {
        userRotated = true
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    // ------------------------------------------------------------------ aspect ratio

    private fun setAspect(m: AspectMode) {
        aspect = m
        b.videoLayout.mode = m          // resizes the video surface; player keeps running
        b.btnAspectLabel.text = m.short
        Prefs.saveAspect(this, m)
        if (m == AspectMode.AUTO) {
            val vs = player.videoSize
            if (vs.width > 0 && vs.height > 0) { userRotated = false; requestedOrientation = orientationFor(vs) }
        }
        showIndicator(R.drawable.ic_aspect, m.label, null)
        hideIndicatorSoon()
        scheduleHide()
    }

    private fun cycleAspect() {
        val all = AspectMode.values()
        setAspect(all[(aspect.ordinal + 1) % all.size])
    }

    private fun showAspectDialog() {
        val modes = AspectMode.values()
        val builder = dialog("Aspect ratio")
            .setSingleChoiceItems(modes.map { it.label }.toTypedArray(), aspect.ordinal) { d, i -> setAspect(modes[i]); d.dismiss() }
        if (b.videoLayout.isZoomed) builder.setNeutralButton("Reset zoom") { _, _ -> b.videoLayout.resetZoom() }
        builder.setPositiveButton("Close", null).show()
    }

    // ------------------------------------------------------------------ gestures

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (locked) showUnlockButton() else setControlsVisible(!controlsVisible)
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!locked) handleDoubleTap(e.x)
                return true
            }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (locked || scaling || e1 == null || e2.pointerCount > 1) return false
                handleScroll(e1, e2, distanceX, distanceY)
                return true
            }
        })
        gestures.setIsLongpressEnabled(false)

        scaleGestures = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                if (locked || !Prefs.gesture(this@PlayerActivity, "gesture_pinch_zoom")) return false
                scaling = true
                if (drag == Drag.SEEK) userSeeking = false
                drag = Drag.IGNORE
                return true
            }
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                b.videoLayout.setUserScale(b.videoLayout.userScale * detector.scaleFactor)
                showIndicator(R.drawable.ic_zoom, "Zoom ${(b.videoLayout.userScale * 100).roundToInt()}%", null)
                return true
            }
            override fun onScaleEnd(detector: ScaleGestureDetector) {
                if (b.videoLayout.userScale < 1.05f) {
                    b.videoLayout.resetZoom()
                    showIndicator(R.drawable.ic_zoom, "Zoom reset", null)
                }
                hideIndicatorSoon()
            }
        })

        b.gestureLayer.setOnTouchListener { _, ev ->
            scaleGestures.onTouchEvent(ev)
            gestures.onTouchEvent(ev)
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) endDrag()
            true
        }
    }

    private fun handleDoubleTap(x: Float) {
        val w = b.gestureLayer.width
        val side = when {
            x < w * 0.35f -> -1
            x > w * 0.65f -> 1
            else -> 0
        }
        if (side != 0 && Prefs.gesture(this, "gesture_double_tap_seek") && player.isCurrentMediaItemSeekable) {
            val now = System.currentTimeMillis()
            if (side != lastTapSide || now - lastTapTime > 1000) tapAccum = 0
            lastTapSide = side; lastTapTime = now
            tapAccum += seekIntervalMs
            seekBy(side * seekIntervalMs)
            val tv = if (side < 0) b.tapLeft else b.tapRight
            (if (side < 0) b.tapRight else b.tapLeft).isVisible = false
            tv.text = (if (side < 0) "−" else "+") + "${tapAccum / 1000}s"
            tv.isVisible = true
            handler.removeCallbacks(hideTapRunnable)
            handler.postDelayed(hideTapRunnable, 800)
        } else if (side == 0 && Prefs.gesture(this, "gesture_double_tap_center")) {
            togglePlayPause()
            val playing = player.playWhenReady
            showIndicator(if (playing) R.drawable.ic_play else R.drawable.ic_pause, if (playing) "Play" else "Pause", null)
            hideIndicatorSoon()
        } else {
            setControlsVisible(!controlsVisible)
        }
    }

    private fun handleScroll(e1: MotionEvent, e2: MotionEvent, dx: Float, dy: Float) {
        val w = b.gestureLayer.width.toFloat()
        val h = b.gestureLayer.height.toFloat()
        if (w <= 0f || h <= 0f) return
        if (drag == Drag.NONE) {
            // Ignore swipes starting at the very top/bottom edge (system gesture zones).
            if (e1.y < h * 0.06f || e1.y > h * 0.94f) { drag = Drag.IGNORE; return }
            val tdx = e2.x - e1.x
            val tdy = e2.y - e1.y
            if (abs(tdx) < touchSlop * 1.5f && abs(tdy) < touchSlop * 1.5f) return
            drag = when {
                b.videoLayout.isZoomed -> Drag.PAN
                abs(tdx) > abs(tdy) * 1.2f ->
                    if (Prefs.gesture(this, "gesture_horizontal_seek") && player.isCurrentMediaItemSeekable && player.duration > 0) Drag.SEEK else Drag.IGNORE
                abs(tdy) > abs(tdx) * 1.2f ->
                    if (e1.x < w / 2) { if (Prefs.gesture(this, "gesture_brightness")) Drag.BRIGHTNESS else Drag.IGNORE }
                    else { if (Prefs.gesture(this, "gesture_volume")) Drag.VOLUME else Drag.IGNORE }
                else -> return
            }
            when (drag) {
                Drag.SEEK -> { seekStart = player.currentPosition; seekTarget = seekStart; userSeeking = true }
                Drag.VOLUME -> volumeLevel = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
                Drag.BRIGHTNESS -> brightness = currentBrightness()
                else -> {}
            }
        }
        when (drag) {
            Drag.PAN -> b.videoLayout.panBy(-dx, -dy)
            Drag.SEEK -> {
                val dur = player.duration
                val span = min(dur, 120_000L).coerceAtLeast(30_000L) // full screen width = up to 2 minutes
                seekTarget = (seekStart + ((e2.x - e1.x) / w * span).toLong()).coerceIn(0L, dur)
                val diff = (seekTarget - seekStart) / 1000
                showIndicator(if (diff >= 0) R.drawable.ic_fast_forward else R.drawable.ic_rewind,
                    "${Fmt.time(seekTarget)}\n${if (diff >= 0) "+" else "−"}${abs(diff)}s", null)
                if (controlsVisible) { b.seekBar.progress = seekTarget.toInt(); b.positionText.text = Fmt.time(seekTarget) }
            }
            Drag.BRIGHTNESS -> {
                brightness = (brightness + dy / h).coerceIn(0.01f, 1f)
                window.attributes = window.attributes.apply { screenBrightness = brightness }
                val pct = (brightness * 100).roundToInt()
                showIndicator(R.drawable.ic_brightness, "Brightness $pct%", pct)
            }
            Drag.VOLUME -> {
                val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                volumeLevel = (volumeLevel + dy / h * max * 1.5f).coerceIn(0f, max.toFloat())
                try { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volumeLevel.roundToInt(), 0) } catch (_: SecurityException) { }
                val pct = if (max > 0) (volumeLevel / max * 100).roundToInt() else 0
                showIndicator(if (pct == 0) R.drawable.ic_volume_off else R.drawable.ic_volume, "Volume $pct%", pct)
            }
            else -> {}
        }
    }

    private fun endDrag() {
        if (drag == Drag.SEEK) {
            player.seekTo(seekTarget)
            userSeeking = false
        }
        if (drag != Drag.NONE && drag != Drag.IGNORE) hideIndicatorSoon()
        drag = Drag.NONE
        scaling = false
    }

    private fun currentBrightness(): Float {
        val wb = window.attributes.screenBrightness
        if (wb >= 0f) return wb
        return try { Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f } catch (e: Exception) { 0.5f }
    }

    private fun showIndicator(icon: Int, text: String, progress: Int?) {
        handler.removeCallbacks(hideIndicatorRunnable)
        b.indicatorIcon.setImageResource(icon)
        b.indicatorText.text = text
        b.indicatorBar.isVisible = progress != null
        if (progress != null) b.indicatorBar.progress = progress
        b.indicator.isVisible = true
    }

    private fun hideIndicatorSoon() {
        handler.removeCallbacks(hideIndicatorRunnable)
        handler.postDelayed(hideIndicatorRunnable, 700)
    }

    // ------------------------------------------------------------------ seek preview

    private fun showSeekPreview(posMs: Long) {
        b.seekPreviewTime.text = Fmt.time(posMs)
        b.seekPreview.isVisible = true
        // Follow the thumb horizontally.
        val sb = b.seekBar
        val max = sb.max.coerceAtLeast(1)
        val trackW = sb.width - sb.paddingLeft - sb.paddingRight
        val parentLeft = (sb.parent as View).left + sb.left + b.bottomBar.left
        val x = parentLeft + sb.paddingLeft + trackW * (posMs.toFloat() / max)
        b.seekPreview.post {
            val pw = b.seekPreview.width
            val maxX = (b.controls.width - b.controls.paddingRight - pw).toFloat()
            b.seekPreview.translationX = (x - pw / 2f).coerceIn(b.controls.paddingLeft.toFloat(), maxX.coerceAtLeast(0f))
        }
        val uri = player.currentMediaItem?.localConfiguration?.uri
        if (!isLocalMedia || uri == null) { b.seekPreviewImage.isVisible = false; return }
        previewTarget = posMs
        if (previewBusy) return
        previewBusy = true
        io.execute { previewLoop(uri) }
    }

    private fun previewLoop(uri: Uri) {
        try {
            var done = -2L
            while (previewTarget != done) {
                val t = previewTarget
                done = t
                if (retrieverUri != uri) {
                    retriever?.release()
                    retriever = MediaMetadataRetriever().apply { setDataSource(this@PlayerActivity, uri) }
                    retrieverUri = uri
                }
                val r = retriever ?: break
                val frame: Bitmap? = if (Build.VERSION.SDK_INT >= 27)
                    r.getScaledFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 180)
                else r.getFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let {
                    val scale = 320f / it.width
                    Bitmap.createScaledBitmap(it, 320, (it.height * scale).toInt().coerceAtLeast(1), true)
                }
                runOnUiThread {
                    if (frame != null && b.seekPreview.isVisible) {
                        b.seekPreviewImage.setImageBitmap(frame)
                        b.seekPreviewImage.isVisible = true
                    }
                }
            }
        } catch (e: Exception) {
            runOnUiThread { b.seekPreviewImage.isVisible = false }
        } finally {
            previewBusy = false
        }
    }

    // ------------------------------------------------------------------ dialogs

    private fun dialog(title: String): MaterialAlertDialogBuilder {
        handler.removeCallbacks(hideControlsRunnable)
        return MaterialAlertDialogBuilder(this).setTitle(title).setOnDismissListener {
            scheduleHide()
            if (fullscreen) setFullscreen(true)
        }
    }

    private fun showSpeedDialog() {
        val cur = player.playbackParameters.speed
        val idx = SPEEDS.indexOfFirst { abs(it - cur) < 0.01f }
        dialog("Playback speed")
            .setSingleChoiceItems(SPEEDS.map { formatSpeed(it) }.toTypedArray(), idx) { d, i ->
                player.setPlaybackSpeed(SPEEDS[i])
                Prefs.saveSpeed(this, SPEEDS[i])
                d.dismiss()
            }
            .setPositiveButton("Close", null)
            .show()
    }

    private fun trackLabel(f: Format, n: Int, type: Int): String {
        val parts = ArrayList<String>()
        f.label?.takeIf { it.isNotBlank() }?.let { parts += it }
        f.language?.takeIf { it.isNotBlank() && it != "und" }?.let {
            val name = Locale.forLanguageTag(it).displayName
            if (name.isNotBlank() && parts.none { p -> p.equals(name, true) }) parts += name
        }
        if (type == C.TRACK_TYPE_AUDIO) {
            when (f.channelCount) {
                1 -> parts += "Mono"; 2 -> parts += "Stereo"; 6 -> parts += "5.1"; 8 -> parts += "7.1"
                Format.NO_VALUE -> {}
                else -> parts += "${f.channelCount}ch"
            }
            codecName(f.sampleMimeType)?.let { parts += it }
        } else {
            codecName(f.sampleMimeType ?: f.containerMimeType)?.let { parts += it }
        }
        return if (parts.isEmpty()) "Track $n" else "$n. " + parts.joinToString(" · ")
    }

    private fun codecName(mime: String?): String? = when (mime) {
        null -> null
        MimeTypes.AUDIO_AAC -> "AAC"; MimeTypes.AUDIO_AC3 -> "AC3"; MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "E-AC3"
        MimeTypes.AUDIO_OPUS -> "Opus"; MimeTypes.AUDIO_VORBIS -> "Vorbis"; MimeTypes.AUDIO_FLAC -> "FLAC"
        MimeTypes.AUDIO_MPEG -> "MP3"; MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD -> "DTS"; MimeTypes.AUDIO_TRUEHD -> "TrueHD"
        MimeTypes.TEXT_VTT -> "WebVTT"; MimeTypes.APPLICATION_SUBRIP -> "SRT"; MimeTypes.TEXT_SSA -> "SSA/ASS"
        MimeTypes.APPLICATION_PGS -> "PGS"; MimeTypes.APPLICATION_TTML -> "TTML"; MimeTypes.APPLICATION_CEA608 -> "CC"
        MimeTypes.APPLICATION_TX3G -> "TX3G"; MimeTypes.APPLICATION_DVBSUBS -> "DVB"; MimeTypes.APPLICATION_VOBSUB -> "VobSub"
        else -> null
    }

    private fun showAudioDialog() {
        val options = ArrayList<Pair<Tracks.Group, Int>>()
        val labels = ArrayList<String>()
        player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.forEach { g ->
            for (i in 0 until g.length) if (g.isTrackSupported(i)) {
                options += g to i
                labels += trackLabel(g.getTrackFormat(i), options.size, C.TRACK_TYPE_AUDIO)
            }
        }
        val muted = player.volume == 0f
        val builder = dialog("Audio")
        if (options.isEmpty()) builder.setMessage("No playable audio tracks in this video.")
        else {
            val checked = options.indexOfFirst { (g, i) -> g.isTrackSelected(i) }
            builder.setSingleChoiceItems(labels.toTypedArray(), checked) { d, which ->
                val (g, i) = options[which]
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                    .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
                    .build()
                d.dismiss()
            }
        }
        builder.setNeutralButton(if (muted) "Unmute" else "Mute") { _, _ ->
            player.volume = if (muted) 1f else 0f
            showIndicator(if (muted) R.drawable.ic_volume else R.drawable.ic_volume_off, if (muted) "Sound on" else "Muted", null)
            hideIndicatorSoon()
        }
        builder.setPositiveButton("Close", null).show()
    }

    private sealed class SubOption {
        object Off : SubOption()
        data class Embedded(val group: Tracks.Group, val index: Int) : SubOption()
        object External : SubOption()
    }

    private fun showSubtitleDialog() {
        val options = ArrayList<SubOption>()
        val labels = ArrayList<String>()
        options += SubOption.Off; labels += "Off"
        player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.forEach { g ->
            for (i in 0 until g.length) if (g.isTrackSupported(i)) {
                options += SubOption.Embedded(g, i)
                labels += trackLabel(g.getTrackFormat(i), options.size - 1, C.TRACK_TYPE_TEXT)
            }
        }
        if (externalSubs != null) { options += SubOption.External; labels += "File: ${externalName ?: "external"}" }

        val textDisabled = player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        val checked = when {
            externalActive -> options.indexOf(SubOption.External)
            textDisabled -> 0
            else -> options.indexOfFirst { it is SubOption.Embedded && it.group.isTrackSelected(it.index) }.let { if (it < 0) 0 else it }
        }
        dialog("Subtitles")
            .setSingleChoiceItems(labels.toTypedArray(), checked) { d, which ->
                selectSubtitle(options[which]); d.dismiss()
            }
            .setNeutralButton("Load file") { _, _ -> pickSubtitle.launch(arrayOf("*/*")) }
            .setPositiveButton("Style & timing") { _, _ -> showSubtitleStyleDialog() }
            .show()
    }

    private fun selectSubtitle(o: SubOption) {
        when (o) {
            SubOption.Off -> {
                externalActive = false
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                b.subtitleView.setCues(emptyList())
            }
            is SubOption.Embedded -> {
                externalActive = false
                b.subtitleView.setCues(emptyList())
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(TrackSelectionOverride(o.group.mediaTrackGroup, o.index)).build()
            }
            SubOption.External -> activateExternal()
        }
    }

    private fun activateExternal() {
        externalActive = true
        shownExternal = emptyList()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        renderExternalSubs()
    }

    private fun renderExternalSubs() {
        val subs = externalSubs ?: return
        val t = player.currentPosition - subtitleDelayMs
        val active = subs.filter { t >= it.startMs && t < it.endMs }
        if (active == shownExternal) return
        shownExternal = active
        b.subtitleView.setCues(active.map {
            Cue.Builder().setText(HtmlCompat.fromHtml(it.text, HtmlCompat.FROM_HTML_MODE_LEGACY)).build()
        })
    }

    private fun loadExternalSubtitle(uri: Uri) {
        val name = displayName(uri)
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        when (ext) {
            "srt", "vtt", "webvtt", "txt" -> io.execute {
                val result = try {
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (bytes == null) null else SubtitleParser.parse(SubtitleParser.decode(bytes))
                } catch (e: Exception) { null }
                runOnUiThread {
                    when {
                        result == null -> toast("Couldn't open \"$name\".")
                        result.isEmpty() -> toast("No subtitle lines found in \"$name\". Is it a valid SRT/VTT file?")
                        else -> {
                            externalSubs = result; externalName = name; subtitleDelayMs = 0
                            activateExternal()
                            toast("Loaded ${result.size} subtitle lines")
                        }
                    }
                }
            }
            "ass", "ssa", "ttml", "dfxp", "xml" -> sideloadSubtitle(uri, name, ext)
            else -> toast("Unsupported subtitle format \".$ext\". Use SRT, VTT, ASS/SSA or TTML.")
        }
    }

    /** ASS/SSA/TTML go through the engine's own parsers as a side-loaded track. */
    private fun sideloadSubtitle(uri: Uri, name: String, ext: String) {
        val cur = player.currentMediaItem ?: return
        val mime = if (ext == "ass" || ext == "ssa") MimeTypes.TEXT_SSA else MimeTypes.APPLICATION_TTML
        val idx = player.currentMediaItemIndex
        val pos = player.currentPosition
        val sub = MediaItem.SubtitleConfiguration.Builder(uri).setMimeType(mime).setLabel(name)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()
        val newItem = cur.buildUpon().setSubtitleConfigurations(listOf(sub)).build()
        externalActive = false
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).clearOverridesOfType(C.TRACK_TYPE_TEXT).build()
        player.replaceMediaItem(idx, newItem)
        player.seekTo(idx, pos)
        toast("Added \"$name\" as a subtitle track")
    }

    private fun applySubtitleStyle() {
        val sp = Prefs.sp(this)
        val size = sp.getInt("sub_size", 100)
        val pos = sp.getInt("sub_position", 8)
        val fg = try { Color.parseColor(sp.getString("sub_color", "#FFFFFFFF")) } catch (e: Exception) { Color.WHITE }
        val (bg, edge) = when (sp.getString("sub_bg", "outline")) {
            "shadow" -> Color.TRANSPARENT to CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
            "semi" -> 0x99000000.toInt() to CaptionStyleCompat.EDGE_TYPE_NONE
            "black" -> Color.BLACK to CaptionStyleCompat.EDGE_TYPE_NONE
            else -> Color.TRANSPARENT to CaptionStyleCompat.EDGE_TYPE_OUTLINE
        }
        b.subtitleView.setStyle(CaptionStyleCompat(fg, bg, Color.TRANSPARENT, edge, Color.BLACK, null))
        b.subtitleView.setApplyEmbeddedFontSizes(false)
        b.subtitleView.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * size / 100f)
        b.subtitleView.setBottomPaddingFraction(pos / 100f)
    }

    private fun showSubtitleStyleDialog() {
        val sp = Prefs.sp(this)
        val v = DialogSubtitleStyleBinding.inflate(layoutInflater)
        val colorValues = resources.getStringArray(R.array.sub_color_values)
        val bgValues = resources.getStringArray(R.array.sub_bg_values)
        v.subColor.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, resources.getStringArray(R.array.sub_color_entries))
        v.subBg.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, resources.getStringArray(R.array.sub_bg_entries))
        v.subColor.setSelection(colorValues.indexOf(sp.getString("sub_color", "#FFFFFFFF")).coerceAtLeast(0))
        v.subBg.setSelection(bgValues.indexOf(sp.getString("sub_bg", "outline")).coerceAtLeast(0))
        v.subSize.progress = sp.getInt("sub_size", 100) - 50
        v.subPos.progress = sp.getInt("sub_position", 8)
        v.sizeLabel.text = "Text size: ${sp.getInt("sub_size", 100)}%"
        v.posLabel.text = "Position from bottom: ${sp.getInt("sub_position", 8)}%"

        val delayEnabled = externalSubs != null
        listOf(v.delayMinus1, v.delayMinus, v.delayPlus, v.delayPlus1).forEach { it.isEnabled = delayEnabled }
        fun showDelay() { v.delayValue.text = String.format(Locale.US, "%+.1fs", subtitleDelayMs / 1000f) }
        showDelay()
        fun shift(d: Long) { subtitleDelayMs += d; shownExternal = emptyList(); showDelay(); renderExternalSubs() }
        v.delayMinus1.setOnClickListener { shift(-1000) }
        v.delayMinus.setOnClickListener { shift(-100) }
        v.delayPlus.setOnClickListener { shift(100) }
        v.delayPlus1.setOnClickListener { shift(1000) }

        val seekListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                if (s === v.subSize) { sp.edit().putInt("sub_size", p + 50).apply(); v.sizeLabel.text = "Text size: ${p + 50}%" }
                else { sp.edit().putInt("sub_position", p).apply(); v.posLabel.text = "Position from bottom: $p%" }
                applySubtitleStyle()
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        }
        v.subSize.setOnSeekBarChangeListener(seekListener)
        v.subPos.setOnSeekBarChangeListener(seekListener)
        v.subColor.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, view: View?, pos: Int, id: Long) { sp.edit().putString("sub_color", colorValues[pos]).apply(); applySubtitleStyle() }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        v.subBg.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, view: View?, pos: Int, id: Long) { sp.edit().putString("sub_bg", bgValues[pos]).apply(); applySubtitleStyle() }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        dialog("Subtitle style & timing").setView(v.root).setPositiveButton("Done", null).show()
    }

    private fun showInfoDialog() {
        val item = player.currentMediaItem
        val vf = player.videoFormat
        val af = player.audioFormat
        val vs = player.videoSize
        val sb = StringBuilder()
        fun line(k: String, v: Any?) { if (v != null && v.toString().isNotBlank()) sb.append(k).append(": ").append(v).append('\n') }
        line("Title", item?.mediaMetadata?.title)
        line("Source", item?.localConfiguration?.uri)
        line("Duration", if (player.duration > 0) Fmt.time(player.duration) else if (player.isCurrentMediaItemLive) "Live" else null)
        sb.append('\n')
        if (vs.width > 0) line("Resolution", "${vs.width} × ${vs.height}")
        if (vs.pixelWidthHeightRatio != 1f) line("Pixel aspect", String.format(Locale.US, "%.3f", vs.pixelWidthHeightRatio))
        if (vf != null) {
            if (vf.frameRate > 0) line("Frame rate", String.format(Locale.US, "%.3f fps", vf.frameRate))
            line("Video codec", vf.codecs ?: vf.sampleMimeType)
            line("Container", vf.containerMimeType)
            if (vf.bitrate > 0) line("Video bitrate", "${vf.bitrate / 1000} kbps")
        }
        line("Video decoder", videoDecoder)
        sb.append('\n')
        if (af != null) {
            line("Audio codec", af.codecs ?: af.sampleMimeType)
            if (af.sampleRate > 0) line("Sample rate", "${af.sampleRate} Hz")
            if (af.channelCount > 0) line("Channels", af.channelCount)
            line("Audio language", af.language?.let { Locale.forLanguageTag(it).displayName })
            if (af.bitrate > 0) line("Audio bitrate", "${af.bitrate / 1000} kbps")
        }
        line("Audio decoder", audioDecoder)
        sb.append('\n')
        line("Display mode", aspect.label)
        line("Speed", formatSpeed(player.playbackParameters.speed))
        dialog("Video info").setMessage(sb.toString().trim()).setPositiveButton("Close", null).show()
    }

    private fun showMoreMenu(anchor: View) {
        handler.removeCallbacks(hideControlsRunnable)
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, "Playback speed (${formatSpeed(player.playbackParameters.speed)})")
        pm.menu.add(0, 2, 1, "Aspect ratio (${aspect.short})")
        pm.menu.add(0, 3, 2, "Load subtitle file")
        pm.menu.add(0, 4, 3, "Video info")
        if (pipSupported()) pm.menu.add(0, 5, 4, "Picture-in-picture")
        pm.menu.add(0, 6, 5, "Background audio").apply { isCheckable = true; isChecked = Prefs.backgroundAudio(this@PlayerActivity) }
        pm.menu.add(0, 7, 6, "Lock screen")
        pm.menu.add(0, 8, 7, "All settings")
        pm.setOnMenuItemClickListener { mi ->
            when (mi.itemId) {
                1 -> showSpeedDialog()
                2 -> showAspectDialog()
                3 -> pickSubtitle.launch(arrayOf("*/*"))
                4 -> showInfoDialog()
                5 -> enterPip()
                6 -> {
                    val on = !Prefs.backgroundAudio(this)
                    Prefs.sp(this).edit().putBoolean("background_audio", on).apply()
                    if (on) connectSession() else disconnectSession()
                    toast(if (on) "Background audio on" else "Background audio off")
                }
                7 -> lockControls()
                8 -> startActivity(Intent(this, SettingsActivity::class.java))
            }
            true
        }
        pm.setOnDismissListener { scheduleHide() }
        pm.show()
    }

    // ------------------------------------------------------------------ PiP & background

    private fun pipSupported() = Build.VERSION.SDK_INT >= 26 && packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun pipParams(): PictureInPictureParams? {
        if (Build.VERSION.SDK_INT < 26) return null
        val vs = player.videoSize
        var ratio = if (vs.width > 0 && vs.height > 0) vs.width * vs.pixelWidthHeightRatio / vs.height else 16f / 9f
        ratio = ratio.coerceIn(1f / 2.39f, 2.39f)
        return PictureInPictureParams.Builder().setAspectRatio(Rational((ratio * 1000).roundToInt(), 1000)).build()
    }

    private fun updatePipParams() {
        if (pipSupported() && Build.VERSION.SDK_INT >= 26) try { pipParams()?.let { setPictureInPictureParams(it) } } catch (_: Exception) { }
    }

    private fun enterPip() {
        if (!pipSupported() || Build.VERSION.SDK_INT < 26) { toast("Picture-in-picture isn't available on this device"); return }
        setControlsVisible(false)
        try { pipParams()?.let { enterPictureInPictureMode(it) } } catch (e: Exception) { toast("Couldn't start picture-in-picture") }
    }

    private fun connectSession() {
        if (controllerFuture != null) return
        try {
            val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
            controllerFuture = MediaController.Builder(this, token).buildAsync()
        } catch (_: Exception) { }
    }

    private fun disconnectSession() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        stopService(Intent(this, PlaybackService::class.java))
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
