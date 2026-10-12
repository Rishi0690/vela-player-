package com.hark.velaplayer

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.slider.Slider
import com.hark.velaplayer.databinding.ItemBrowserBinding
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
        private const val SPEED_MIN = 0.25f
        private const val SPEED_MAX = 4f
        private const val SPEED_STEP = 0.05f
        private const val ACTION_PIP = "com.hark.velaplayer.PIP_CONTROL"
        private const val EXTRA_CONTROL = "control"
        private const val CTRL_PLAY = 1
        private const val CTRL_PAUSE = 2
        private const val CTRL_PREV = 3
        private const val CTRL_NEXT = 4
        private const val CTRL_REWIND = 5
        private const val CTRL_FORWARD = 6

        /** Snaps any speed onto the slider's 0.05 grid within its range. */
        fun snapSpeed(v: Float): Float {
            val steps = ((v.coerceIn(SPEED_MIN, SPEED_MAX) - SPEED_MIN) / SPEED_STEP).roundToInt()
            return ((SPEED_MIN + steps * SPEED_STEP) * 100).roundToInt() / 100f
        }

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

    // Track preferences are applied once per video, after its tracks are known.
    private var tracksAppliedFor: String? = null
    // Subtitle look used for this session when "Remember subtitle appearance" is off.
    private val sessionStyle = HashMap<String, Any>()

    // Skip feedback (buttons and double-tap share it).
    private var seekFbSide = 0
    private var seekFbAccum = 0L
    private var seekFbTime = 0L
    private var dragSide = "left"

    // Speed popup
    private var speedPanelOpen = false

    // Quick video browser
    private var library: List<VideoItem>? = null
    private var libraryLoading = false
    private var browserOpen = false
    private var browserScopeAll = false
    private var browserFolderId: String? = null
    private var browserFolderName: String? = null
    private var browserQuery = ""
    private val browserAdapter = BrowserAdapter()

    private val hideControlsRunnable = Runnable { setControlsVisible(false) }
    private val hideIndicatorRunnable = Runnable { b.indicator.isVisible = false }
    private val hideTapRunnable = Runnable { fadeOut(b.tapLeft); fadeOut(b.tapRight); seekFbAccum = 0L }
    private val hideSideRunnable = Runnable { fadeOut(b.sideLeft); fadeOut(b.sideRight) }

    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!playerReady || intent?.action != ACTION_PIP) return
            when (intent.getIntExtra(EXTRA_CONTROL, 0)) {
                CTRL_PLAY -> togglePlayPauseTo(true)
                CTRL_PAUSE -> togglePlayPauseTo(false)
                CTRL_PREV -> { savePosition(); player.seekToPreviousMediaItem() }
                CTRL_NEXT -> { savePosition(); player.seekToNextMediaItem() }
                CTRL_REWIND -> seekBy(-seekIntervalMs)
                CTRL_FORWARD -> seekBy(seekIntervalMs)
            }
            updatePipParams()
        }
    }
    private val hideUnlockRunnable = Runnable { setUnlockVisible(false) }
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
            b.edgeOverlay.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            val pad = dp(12)
            b.browserPanel.setPadding(0, safe.top, safe.right, safe.bottom)
            b.browserList.setPadding(dp(8), 0, dp(8), pad)
            (b.speedPanel.layoutParams as? android.widget.FrameLayout.LayoutParams)?.let {
                it.bottomMargin = safe.bottom + dp(92); b.speedPanel.layoutParams = it
            }
            insets
        }
        setFullscreen(true)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    browserOpen -> closeBrowser()
                    speedPanelOpen -> hideSpeedPanel()
                    locked -> setUnlockVisible(true)
                    else -> finish()
                }
            }
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
        setupSpeedPanel()
        setupBrowser()
        if (!pipSupported()) b.btnPip.isVisible = false
        ContextCompat.registerReceiver(this, pipReceiver, IntentFilter(ACTION_PIP), ContextCompat.RECEIVER_NOT_EXPORTED)

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
        try { unregisterReceiver(pipReceiver) } catch (_: Exception) { }
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
        // Android 12+ enters PiP on its own (setAutoEnterEnabled); older versions need a manual call.
        if (Build.VERSION.SDK_INT < 31 && Prefs.autoPip(this) && player.isPlaying && pipSupported()) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            setControlsVisible(false)
            b.indicator.isVisible = false
            b.btnUnlock.isVisible = false
            b.btnUnlockRight.isVisible = false
            if (browserOpen) closeBrowser(false)
            if (speedPanelOpen) hideSpeedPanel()
            b.edgeOverlay.isVisible = false
            b.pipProgress.isVisible = true
            updatePipProgress()
            updatePipParams()
        } else {
            b.edgeOverlay.isVisible = true
            b.pipProgress.isVisible = false
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
        startQueue(entries, intent.getIntExtra(EXTRA_INDEX, 0))
        return true
    }

    /** Replaces the queue and starts playing [index]. Speed, aspect, subtitle look etc. carry over. */
    private fun startQueue(entries: List<Entry>, index: Int) {
        val start = index.coerceIn(0, entries.lastIndex)
        val startPos = if (Prefs.resume(this)) Store.position(this, entries[start].uri) else 0L
        externalSubs = null; externalActive = false; shownExternal = emptyList()
        tracksAppliedFor = null
        b.errorPanel.isVisible = false
        player.setMediaItems(entries.map { buildItem(it) }, start, startPos)
        player.setPlaybackSpeed(Prefs.speed(this))
        player.pauseAtEndOfMediaItems = !Prefs.autoplayNext(this)
        player.prepare()
        player.playWhenReady = true
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
                player.currentMediaItem?.let { Store.markWatched(this@PlayerActivity, it.mediaId) }
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
            updatePipParams()
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
            resetTrackParamsForNewItem()
            updatePipParams()
            if (browserOpen) renderBrowser(false)
        }

        override fun onTracksChanged(tracks: Tracks) {
            val id = player.currentMediaItem?.mediaId ?: return
            if (tracksAppliedFor == id || tracks.groups.isEmpty()) return
            tracksAppliedFor = id
            applyRememberedTracks(tracks)
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                oldPosition.mediaItem?.let { Store.markWatched(this@PlayerActivity, it.mediaId) }
            }
        }

        override fun onPlayerError(error: PlaybackException) { showError(error) }

        override fun onCues(cueGroup: CueGroup) { if (!externalActive) b.subtitleView.setCues(cueGroup.cues) }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            b.btnSpeed.text = formatSpeed(playbackParameters.speed)
            if (speedPanelOpen) refreshSpeedPanel(playbackParameters.speed)
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) { updateNavButtons(); updateProgress(); updatePipParams() }
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
        b.btnRewind.setOnClickListener { showSeekFeedback(-1, seekBy(-seekIntervalMs)); scheduleHide() }
        b.btnForward.setOnClickListener { showSeekFeedback(1, seekBy(seekIntervalMs)); scheduleHide() }
        b.btnPrev.setOnClickListener { savePosition(); player.seekToPreviousMediaItem(); scheduleHide() }
        b.btnNext.setOnClickListener { savePosition(); player.seekToNextMediaItem(); scheduleHide() }
        b.btnSpeed.setOnClickListener { showSpeedPanel(sliderOnly = false) }
        b.btnSpeed.setOnLongClickListener { showSpeedPanel(sliderOnly = true); true }
        b.btnBrowse.setOnClickListener { openBrowser() }
        b.btnAspect.setOnClickListener { showAspectDialog() }
        b.btnAspect.setOnLongClickListener { cycleAspect(); true }
        b.btnAspectLabel.setOnClickListener { cycleAspect() }
        b.btnAspectLabel.setOnLongClickListener { showAspectDialog(); true }
        b.btnSubtitle.setOnClickListener { showSubtitleDialog() }
        b.btnAudio.setOnClickListener { showAudioDialog() }
        b.btnMore.setOnClickListener { showMoreMenu(it) }
        b.btnLock.setOnClickListener { lockControls() }
        b.btnLockRight.setOnClickListener { lockControls() }
        b.btnUnlock.setOnClickListener { unlockControls() }
        b.btnUnlockRight.setOnClickListener { unlockControls() }
        b.btnRotate.setOnClickListener { rotateScreen(); scheduleHide() }
        b.btnPip.setOnClickListener { enterPip() }
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

    private fun togglePlayPauseTo(play: Boolean) {
        if (play) {
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(player.currentMediaItemIndex, 0)
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.play()
        } else player.pause()
    }

    /** Seeks by [delta] (clamped to the video) and returns how far it actually moved, in ms (signed). */
    private fun seekBy(delta: Long): Long {
        if (!player.isCurrentMediaItemSeekable) return 0L
        val dur = player.duration
        val from = player.currentPosition
        var t = from + delta
        if (t < 0) t = 0
        if (dur != C.TIME_UNSET && t > dur) t = dur
        player.seekTo(t)
        updateProgress()
        return t - from
    }

    /**
     * Shared skip feedback for the skip buttons and double-tap: shows the real amount moved near that side,
     * adding up repeated taps in the same direction.
     */
    private fun showSeekFeedback(side: Int, movedMs: Long) {
        if (!player.isCurrentMediaItemSeekable) return
        val now = SystemClock.uptimeMillis()
        if (side != seekFbSide || now - seekFbTime > 1000) seekFbAccum = 0L
        seekFbSide = side; seekFbTime = now
        seekFbAccum += abs(movedMs)
        val secs = (seekFbAccum + 500) / 1000
        val box = if (side < 0) b.tapLeft else b.tapRight
        val text = if (side < 0) b.tapLeftText else b.tapRightText
        handler.removeCallbacks(hideTapRunnable)
        (if (side < 0) b.tapRight else b.tapLeft).let { it.animate().cancel(); it.isVisible = false }
        text.text = when {
            secs == 0L -> if (side < 0) "At start" else "At end"
            else -> (if (side < 0) "−" else "+") + secs + if (secs == 1L) " second" else " seconds"
        }
        fadeIn(box)
        handler.postDelayed(hideTapRunnable, 900)
    }

    private fun fadeIn(v: View) {
        v.animate().cancel()
        if (!v.isVisible) { v.alpha = 0f; v.isVisible = true }
        v.animate().alpha(1f).setDuration(120).start()
    }

    private fun fadeOut(v: View) {
        if (!v.isVisible) return
        v.animate().cancel()
        v.animate().alpha(0f).setDuration(220).withEndAction { v.isVisible = false }.start()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

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
        if (b.pipProgress.isVisible) updatePipProgress()
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
        if (speedPanelOpen) hideSpeedPanel()
        if (browserOpen) closeBrowser(false)
        locked = true
        setUnlockVisible(true)
        Toast.makeText(this, "Screen locked. Tap the screen, then a lock button to unlock.", Toast.LENGTH_SHORT).show()
    }

    /** While locked: unlock buttons on both sides. A tap on the screen shows/hides them; they also hide after a few seconds. */
    private fun setUnlockVisible(show: Boolean) {
        handler.removeCallbacks(hideUnlockRunnable)
        if (show) {
            fadeIn(b.btnUnlock); fadeIn(b.btnUnlockRight)
            handler.postDelayed(hideUnlockRunnable, 3500)
        } else {
            fadeOut(b.btnUnlock); fadeOut(b.btnUnlockRight)
        }
    }

    private fun toggleUnlockButtons() = setUnlockVisible(!b.btnUnlock.isVisible || b.btnUnlock.alpha < 0.5f)

    private fun unlockControls() {
        locked = false
        handler.removeCallbacks(hideUnlockRunnable)
        b.btnUnlock.animate().cancel(); b.btnUnlockRight.animate().cancel()
        b.btnUnlock.isVisible = false; b.btnUnlockRight.isVisible = false
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
                if (speedPanelOpen) { hideSpeedPanel(); return true }
                if (locked) toggleUnlockButtons() else setControlsVisible(!controlsVisible)
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
            showSeekFeedback(side, seekBy(side * seekIntervalMs))
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
                abs(tdy) > abs(tdx) * 1.2f -> verticalDragFor(e1.x, w)
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
                showSideLevel(R.drawable.ic_brightness, pct)
            }
            Drag.VOLUME -> {
                val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                volumeLevel = (volumeLevel + dy / h * max * 1.5f).coerceIn(0f, max.toFloat())
                try { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volumeLevel.roundToInt(), 0) } catch (_: SecurityException) { }
                val pct = if (max > 0) (volumeLevel / max * 100).roundToInt() else 0
                showSideLevel(if (pct == 0) R.drawable.ic_volume_off else R.drawable.ic_volume, pct)
            }
            else -> {}
        }
    }

    private fun endDrag() {
        if (drag == Drag.SEEK) {
            player.seekTo(seekTarget)
            userSeeking = false
        }
        when (drag) {
            Drag.BRIGHTNESS, Drag.VOLUME -> { handler.removeCallbacks(hideSideRunnable); handler.postDelayed(hideSideRunnable, 800) }
            Drag.NONE, Drag.IGNORE -> {}
            else -> hideIndicatorSoon()
        }
        drag = Drag.NONE
        scaling = false
    }

    /**
     * Which vertical gesture a swipe starting at [x] controls, from the user's side settings.
     * If both brightness and volume are set to the same side, that half is split: the outer part (near the edge)
     * is brightness and the inner part is volume.
     */
    private fun verticalDragFor(x: Float, w: Float): Drag {
        val side = if (x < w / 2) "left" else "right"
        val wantsB = Prefs.gesture(this, "gesture_brightness") && Prefs.brightnessSide(this) == side
        val wantsV = Prefs.gesture(this, "gesture_volume") && Prefs.volumeSide(this) == side
        dragSide = side
        return when {
            wantsB && wantsV -> {
                val outer = if (side == "left") x < w / 4 else x > w * 3 / 4
                if (outer) Drag.BRIGHTNESS else Drag.VOLUME
            }
            wantsB -> Drag.BRIGHTNESS
            wantsV -> Drag.VOLUME
            else -> Drag.IGNORE
        }
    }

    /** Brightness/volume level pill, shown at the edge of the side being swiped. */
    private fun showSideLevel(icon: Int, pct: Int) {
        handler.removeCallbacks(hideSideRunnable)
        val left = dragSide == "left"
        val box = if (left) b.sideLeft else b.sideRight
        val other = if (left) b.sideRight else b.sideLeft
        val track = if (left) b.sideLeftTrack else b.sideRightTrack
        val fill = if (left) b.sideLeftFill else b.sideRightFill
        (if (left) b.sideLeftIcon else b.sideRightIcon).setImageResource(icon)
        (if (left) b.sideLeftText else b.sideRightText).text = "$pct%"
        val trackH = if (track.height > 0) track.height else dp(120)
        fill.layoutParams = fill.layoutParams.apply { height = (trackH * pct.coerceIn(0, 100) / 100f).roundToInt() }
        if (other.isVisible) { other.animate().cancel(); other.isVisible = false }
        b.indicator.isVisible = false
        fadeIn(box)
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

    // ------------------------------------------------------------------ speed popup

    private fun setupSpeedPanel() {
        b.speedSlider.setLabelFormatter { String.format(Locale.US, "%.2fx", it) }
        b.speedSlider.addOnChangeListener { _, value, fromUser -> if (fromUser) applySpeed(value) }
        b.speedSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) { handler.removeCallbacks(hideControlsRunnable) }
            override fun onStopTrackingTouch(slider: Slider) { applySpeed(slider.value) }
        })
        SPEEDS.forEach { sp ->
            val chip = layoutInflater.inflate(R.layout.chip_speed, b.speedPresets, false) as Chip
            chip.id = View.generateViewId()
            chip.tag = sp
            chip.text = if (sp == 1f) "1x · Normal" else formatSpeed(sp)
            if (sp == 1f) {
                chip.chipStrokeWidth = dp(2).toFloat()
                chip.chipStrokeColor = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, R.color.accent))
            }
            chip.setOnClickListener { applySpeed(sp) }
            b.speedPresets.addView(chip)
        }
        b.speedReset.setOnClickListener { applySpeed(1f) }
        b.speedClose.setOnClickListener { hideSpeedPanel() }
    }

    /** Changes the real playback speed and remembers it (Prefs decides whether it's used next time). */
    private fun applySpeed(v: Float) {
        val s = snapSpeed(v)
        player.setPlaybackSpeed(s)
        Prefs.saveSpeed(this, s)
        refreshSpeedPanel(s)
    }

    private fun refreshSpeedPanel(speed: Float) {
        val s = snapSpeed(speed)
        b.speedValue.text = String.format(Locale.US, "%.2fx", speed)
        b.speedNormalTag.isVisible = abs(speed - 1f) < 0.001f
        if (abs(b.speedSlider.value - s) > 0.001f) b.speedSlider.value = s
        var found = false
        for (i in 0 until b.speedPresets.childCount) {
            val c = b.speedPresets.getChildAt(i) as Chip
            val match = abs((c.tag as Float) - speed) < 0.001f
            c.isChecked = match
            if (match) found = true
        }
        if (!found) b.speedPresets.clearCheck()
    }

    /** Tap: presets + slider. Press and hold: compact slider-only view. Speed changes apply instantly. */
    private fun showSpeedPanel(sliderOnly: Boolean) {
        if (browserOpen) closeBrowser(false)
        val avail = (if (b.root.width > 0) b.root.width else resources.displayMetrics.widthPixels) - dp(24)
        b.speedPanel.layoutParams = b.speedPanel.layoutParams.apply { width = min(avail, dp(440)) }
        b.speedPresetScroll.isVisible = !sliderOnly
        refreshSpeedPanel(player.playbackParameters.speed)
        speedPanelOpen = true
        handler.removeCallbacks(hideControlsRunnable)
        fadeIn(b.speedPanel)
        b.speedPanel.translationY = dp(16).toFloat()
        b.speedPanel.animate().translationY(0f).alpha(1f).setDuration(160).start()
        if (!sliderOnly) b.speedPresets.post {
            val checked = b.speedPresets.findViewById<View>(b.speedPresets.checkedChipId)
            if (checked != null) b.speedPresetScroll.smoothScrollTo((checked.left - dp(60)).coerceAtLeast(0), 0)
        }
    }

    private fun hideSpeedPanel() {
        speedPanelOpen = false
        fadeOut(b.speedPanel)
        if (fullscreen) setFullscreen(true)
        scheduleHide()
    }

    private fun showSpeedDialog() = showSpeedPanel(sliderOnly = false)

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
                Prefs.saveAudioLang(this, g.getTrackFormat(i).language)
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
                Prefs.saveSubChoice(this, false, null, null)
                externalActive = false
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                b.subtitleView.setCues(emptyList())
            }
            is SubOption.Embedded -> {
                val f = o.group.getTrackFormat(o.index)
                Prefs.saveSubChoice(this, true, f.label, f.language)
                externalActive = false
                b.subtitleView.setCues(emptyList())
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(TrackSelectionOverride(o.group.mediaTrackGroup, o.index)).build()
            }
            SubOption.External -> { Prefs.saveSubChoice(this, true, null, null); activateExternal() }
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
                            externalSubs = result; externalName = name; subtitleDelayMs = Prefs.subDelay(this)
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

    // Subtitle look: saved to settings when "Remember subtitle appearance" is on, otherwise kept for this session only.
    private fun styleInt(k: String, def: Int): Int =
        if (Prefs.rememberSubStyle(this)) Prefs.sp(this).getInt(k, def) else (sessionStyle[k] as? Int) ?: Prefs.sp(this).getInt(k, def)
    private fun styleStr(k: String, def: String): String =
        if (Prefs.rememberSubStyle(this)) Prefs.sp(this).getString(k, def) ?: def
        else (sessionStyle[k] as? String) ?: Prefs.sp(this).getString(k, def) ?: def
    private fun putStyle(k: String, v: Any) {
        if (Prefs.rememberSubStyle(this)) {
            val e = Prefs.sp(this).edit()
            if (v is Int) e.putInt(k, v) else e.putString(k, v.toString())
            e.apply()
        } else sessionStyle[k] = v
    }

    private fun applySubtitleStyle() {
        val size = styleInt("sub_size", 100)
        val pos = styleInt("sub_position", 8)
        val fg = try { Color.parseColor(styleStr("sub_color", "#FFFFFFFF")) } catch (e: Exception) { Color.WHITE }
        val (bg, edge) = when (styleStr("sub_bg", "outline")) {
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
        val v = DialogSubtitleStyleBinding.inflate(layoutInflater)
        val colorValues = resources.getStringArray(R.array.sub_color_values)
        val bgValues = resources.getStringArray(R.array.sub_bg_values)
        v.subColor.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, resources.getStringArray(R.array.sub_color_entries))
        v.subBg.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, resources.getStringArray(R.array.sub_bg_entries))
        v.subColor.setSelection(colorValues.indexOf(styleStr("sub_color", "#FFFFFFFF")).coerceAtLeast(0))
        v.subBg.setSelection(bgValues.indexOf(styleStr("sub_bg", "outline")).coerceAtLeast(0))
        v.subSize.progress = styleInt("sub_size", 100) - 50
        v.subPos.progress = styleInt("sub_position", 8)
        v.sizeLabel.text = "Text size: ${styleInt("sub_size", 100)}%"
        v.posLabel.text = "Position from bottom: ${styleInt("sub_position", 8)}%"

        val delayEnabled = externalSubs != null
        listOf(v.delayMinus1, v.delayMinus, v.delayPlus, v.delayPlus1).forEach { it.isEnabled = delayEnabled }
        fun showDelay() { v.delayValue.text = String.format(Locale.US, "%+.1fs", subtitleDelayMs / 1000f) }
        showDelay()
        fun shift(d: Long) { subtitleDelayMs += d; Prefs.saveSubDelay(this, subtitleDelayMs); shownExternal = emptyList(); showDelay(); renderExternalSubs() }
        v.delayMinus1.setOnClickListener { shift(-1000) }
        v.delayMinus.setOnClickListener { shift(-100) }
        v.delayPlus.setOnClickListener { shift(100) }
        v.delayPlus1.setOnClickListener { shift(1000) }

        val seekListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                if (s === v.subSize) { putStyle("sub_size", p + 50); v.sizeLabel.text = "Text size: ${p + 50}%" }
                else { putStyle("sub_position", p); v.posLabel.text = "Position from bottom: $p%" }
                applySubtitleStyle()
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        }
        v.subSize.setOnSeekBarChangeListener(seekListener)
        v.subPos.setOnSeekBarChangeListener(seekListener)
        v.subColor.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, view: View?, pos: Int, id: Long) { putStyle("sub_color", colorValues[pos]); applySubtitleStyle() }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        v.subBg.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, view: View?, pos: Int, id: Long) { putStyle("sub_bg", bgValues[pos]); applySubtitleStyle() }
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
        pm.menu.add(0, 9, 8, "Browse videos")
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
                9 -> openBrowser()
            }
            true
        }
        pm.setOnDismissListener { scheduleHide() }
        pm.show()
    }

    // ------------------------------------------------------------------ PiP & background

    private fun pipSupported() = Build.VERSION.SDK_INT >= 26 && Prefs.pipEnabled(this) &&
        packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun pipAction(icon: Int, title: String, ctrl: Int, enabled: Boolean = true): RemoteAction? {
        if (Build.VERSION.SDK_INT < 26) return null
        val pi = PendingIntent.getBroadcast(
            this, ctrl, Intent(ACTION_PIP).setPackage(packageName).putExtra(EXTRA_CONTROL, ctrl),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return RemoteAction(Icon.createWithResource(this, icon), title, title, pi).apply { isEnabled = enabled }
    }

    /**
     * PiP buttons that drive the real player. Android shows a limited number (usually 3), so the most useful
     * ones win: play/pause always, then previous/next when there's a queue (or the Settings choice), else rewind/forward.
     */
    private fun pipActions(): List<RemoteAction> {
        if (Build.VERSION.SDK_INT < 26) return emptyList()
        val max = (try { maxNumPictureInPictureActions } catch (_: Exception) { 3 }).coerceAtLeast(1)
        val hasQueue = player.mediaItemCount > 1
        val useTrack = when (Prefs.pipControls(this)) { "track" -> true; "seek" -> false; else -> hasQueue }
        val playing = player.playWhenReady && player.playbackState != Player.STATE_ENDED && player.playbackState != Player.STATE_IDLE
        val secs = seekIntervalMs / 1000
        val play = if (playing) pipAction(R.drawable.ic_pip_pause, "Pause", CTRL_PAUSE) else pipAction(R.drawable.ic_pip_play, "Play", CTRL_PLAY)
        val prev = pipAction(R.drawable.ic_pip_skip_prev, "Previous video", CTRL_PREV, player.hasPreviousMediaItem())
        val next = pipAction(R.drawable.ic_pip_skip_next, "Next video", CTRL_NEXT, player.hasNextMediaItem())
        val seekable = player.isCurrentMediaItemSeekable
        val rew = pipAction(R.drawable.ic_pip_rewind, "Rewind $secs seconds", CTRL_REWIND, seekable)
        val fwd = pipAction(R.drawable.ic_pip_fast_forward, "Forward $secs seconds", CTRL_FORWARD, seekable)
        // Display order (left to right) and priority (what survives when space is short).
        val display = listOf(rew, prev, play, next, fwd)
        val priority = if (useTrack) listOf(play, next, prev, fwd, rew) else listOf(play, fwd, rew, next, prev)
        val keep = priority.filterNotNull().take(max).toSet()
        return display.filterNotNull().filter { it in keep }
    }

    private fun pipParams(): PictureInPictureParams? {
        if (Build.VERSION.SDK_INT < 26) return null
        val vs = player.videoSize
        var ratio = if (vs.width > 0 && vs.height > 0) vs.width * vs.pixelWidthHeightRatio / vs.height else 16f / 9f
        ratio = ratio.coerceIn(1f / 2.39f, 2.39f)
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational((ratio * 1000).roundToInt(), 1000))
            .setActions(pipActions())
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setAutoEnterEnabled(Prefs.autoPip(this) && player.isPlaying)
            builder.setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    private fun updatePipParams() {
        if (!playerReady) return
        if (pipSupported() && Build.VERSION.SDK_INT >= 26) try { pipParams()?.let { setPictureInPictureParams(it) } } catch (_: Exception) { }
    }

    private fun updatePipProgress() {
        val dur = player.duration
        b.pipProgress.progress = if (dur != C.TIME_UNSET && dur > 0) (player.currentPosition * 1000 / dur).toInt() else 0
    }

    private fun enterPip() {
        if (!pipSupported() || Build.VERSION.SDK_INT < 26) { toast("Picture-in-picture is off or not available on this device"); return }
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

    // ------------------------------------------------------------------ remembered track preferences

    /** Normalises "hi", "hin", "hi-IN", "en_US" etc. to one ISO-639-2 code so languages compare reliably. */
    private fun langKey(x: String?): String? {
        if (x.isNullOrBlank() || x == "und") return null
        val base = x.lowercase(Locale.ROOT).substringBefore('-').substringBefore('_')
        return try { Locale(base).isO3Language.ifEmpty { base } } catch (_: Exception) { base }
    }
    private fun sameLang(a: String?, b2: String?): Boolean { val x = langKey(a); return x != null && x == langKey(b2) }

    /** New video: drop the previous video's manual picks and re-apply the remembered defaults. */
    private fun resetTrackParamsForNewItem() {
        tracksAppliedFor = null
        val pb = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setPreferredAudioLanguage(Prefs.preferredAudioLang(this))
        val state = Prefs.rememberedSubState(this)
        pb.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, state == "off")
        val subLang = Prefs.preferredSubLang(this)
        if (state == "on" || Prefs.sp(this).getBoolean("sub_auto", false)) {
            pb.setPreferredTextLanguage(subLang ?: Locale.getDefault().language).setSelectUndeterminedTextLanguage(true)
        } else pb.setPreferredTextLanguage(subLang)
        player.trackSelectionParameters = pb.build()
    }

    /**
     * Picks the remembered audio language and subtitle track for this video when it has them.
     * Anything missing falls back to the normal default without interrupting playback.
     */
    private fun applyRememberedTracks(tracks: Tracks) {
        var params = player.trackSelectionParameters.buildUpon()
        var changed = false

        val audioLang = if (Prefs.rememberAudioLang(this)) Prefs.sp(this).getString("last_audio_lang", null) else null
        if (audioLang != null) {
            val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
            val selectedOk = audio.any { g -> (0 until g.length).any { g.isTrackSelected(it) && sameLang(g.getTrackFormat(it).language, audioLang) } }
            if (!selectedOk) {
                loop@ for (g in audio) for (i in 0 until g.length) {
                    if (g.isTrackSupported(i) && sameLang(g.getTrackFormat(i).language, audioLang)) {
                        params = params.setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)); changed = true; break@loop
                    }
                }
            }
        }

        if (Prefs.rememberedSubState(this) == "on") {
            val texts = ArrayList<Pair<Tracks.Group, Int>>()
            tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.forEach { g ->
                for (i in 0 until g.length) if (g.isTrackSupported(i)) texts += g to i
            }
            if (texts.isNotEmpty()) {
                val label = Prefs.rememberedSubLabel(this)
                val lang = Prefs.preferredSubLang(this)
                fun fmt(p: Pair<Tracks.Group, Int>) = p.first.getTrackFormat(p.second)
                val pick = texts.firstOrNull { label != null && fmt(it).label == label && (lang == null || sameLang(fmt(it).language, lang)) }
                    ?: texts.firstOrNull { lang != null && sameLang(fmt(it).language, lang) }
                    ?: texts.firstOrNull { it.first.isTrackSelected(it.second) }
                    ?: texts.firstOrNull { (fmt(it).selectionFlags and C.SELECTION_FLAG_DEFAULT) != 0 }
                    ?: texts.first()
                if (!pick.first.isTrackSelected(pick.second)) {
                    params = params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(TrackSelectionOverride(pick.first.mediaTrackGroup, pick.second))
                    changed = true
                }
            }
        }
        if (changed) player.trackSelectionParameters = params.build()
    }

    // ------------------------------------------------------------------ quick video browser

    private fun hasVideoPermission(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) return true
        return Build.VERSION.SDK_INT >= 34 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
    }

    private fun setupBrowser() {
        b.browserList.layoutManager = LinearLayoutManager(this)
        b.browserList.adapter = browserAdapter
        b.browserClose.setOnClickListener { closeBrowser() }
        b.browserScrim.setOnClickListener { closeBrowser() }
        b.browserScope.setOnCheckedStateChangeListener { _, ids ->
            browserScopeAll = ids.firstOrNull() == R.id.chipScopeAll
            renderBrowser(true)
        }
        b.browserSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(e: Editable?) { browserQuery = e?.toString() ?: ""; renderBrowser(false) }
        })
    }

    private fun openBrowser() {
        if (browserOpen) return
        if (speedPanelOpen) hideSpeedPanel()
        setControlsVisible(false)
        browserOpen = true
        val w = (if (b.root.width > 0) b.root.width else resources.displayMetrics.widthPixels)
        val panelW = min(dp(380), (w * 0.88f).toInt())
        b.browserPanel.layoutParams = b.browserPanel.layoutParams.apply { width = panelW }
        b.browserPanel.translationX = panelW.toFloat()
        b.browserPanel.isVisible = true
        b.browserPanel.animate().cancel()
        b.browserPanel.animate().translationX(0f).setDuration(220).start()
        fadeIn(b.browserScrim)
        if (!hasVideoPermission()) {
            library = emptyList()
            renderBrowser(true)
            return
        }
        if (library == null) {
            b.browserEmpty.text = "Loading videos…"; b.browserEmpty.isVisible = true
            loadLibraryForBrowser()
        } else renderBrowser(true)
    }

    private fun loadLibraryForBrowser() {
        if (libraryLoading) return
        libraryLoading = true
        io.execute {
            val items = VideoRepository.load(this)
            runOnUiThread {
                libraryLoading = false
                library = items
                if (browserOpen) renderBrowser(true)
            }
        }
    }

    private fun closeBrowser(animate: Boolean = true) {
        browserOpen = false
        val panel = b.browserPanel
        panel.animate().cancel()
        if (animate) panel.animate().translationX(panel.width.toFloat()).setDuration(200).withEndAction { if (!browserOpen) panel.isVisible = false }.start()
        else panel.isVisible = false
        fadeOut(b.browserScrim)
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        imm?.hideSoftInputFromWindow(b.browserSearch.windowToken, 0)
        if (fullscreen) setFullscreen(true)
    }

    /** The library entry for what's playing now (matched by its content uri or its file path). */
    private fun currentLibraryItem(): VideoItem? {
        val lib = library ?: return null
        val id = player.currentMediaItem?.mediaId ?: return null
        lib.firstOrNull { it.uri.toString() == id }?.let { return it }
        val u = Uri.parse(id)
        val path = if (u.scheme == "file") u.path else null
        if (path != null) lib.firstOrNull { it.path == path }?.let { return it }
        val name = player.currentMediaItem?.mediaMetadata?.title?.toString()
        return lib.firstOrNull { name != null && it.name == name && it.durationMs > 0 && abs(it.durationMs - player.duration) < 2000 }
    }

    private fun renderBrowser(scrollToCurrent: Boolean) {
        val lib = library ?: return
        val cur = currentLibraryItem()
        if (cur != null) { browserFolderId = cur.bucketId; browserFolderName = cur.bucketName }
        val folderKnown = browserFolderId != null
        b.chipScopeFolder.isEnabled = folderKnown
        b.chipScopeFolder.text = browserFolderName?.let { "Folder: $it" } ?: "This folder"
        if (!folderKnown && !browserScopeAll) { browserScopeAll = true; b.chipScopeAll.isChecked = true; return }
        if (browserScopeAll != b.chipScopeAll.isChecked) {
            if (browserScopeAll) b.chipScopeAll.isChecked = true else b.chipScopeFolder.isChecked = true
            return // the chip listener renders again with the new scope
        }

        val scope = if (browserScopeAll) lib
        else lib.filter { it.bucketId == browserFolderId }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        val q = browserQuery.trim().lowercase()
        val shown = if (q.isEmpty()) scope else scope.filter { it.name.lowercase().contains(q) }

        val countText = if (scope.size == 1) "1 video" else "${scope.size} videos"
        if (browserScopeAll) {
            b.browserTitle.text = "All videos"
            b.browserSubtitle.text = countText
        } else {
            b.browserTitle.text = browserFolderName ?: "This folder"
            val path = scope.firstOrNull()?.folderPath
            b.browserSubtitle.text = if (path != null) "$countText · $path" else countText
        }
        browserAdapter.submit(shown, scope)
        b.browserEmpty.isVisible = shown.isEmpty()
        b.browserEmpty.text = when {
            !hasVideoPermission() -> "Allow video access on the home screen to browse your videos here."
            q.isNotEmpty() -> "Nothing matches \"$browserQuery\"."
            else -> "No videos here."
        }
        if (scrollToCurrent) {
            val idx = shown.indexOfFirst { it.uri.toString() == player.currentMediaItem?.mediaId }
            if (idx >= 0) (b.browserList.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(idx, dp(80))
        }
    }

    /** Plays [item] right away, queueing the rest of the list it was picked from (so next/previous follow that list). */
    private fun playFromBrowser(item: VideoItem, scope: List<VideoItem>) {
        savePosition()
        val idx = scope.indexOf(item).coerceAtLeast(0)
        val from = (idx - 250).coerceAtLeast(0)
        val to = (from + 500).coerceAtMost(scope.size)
        val window = scope.subList(from, to)
        browserFolderId = item.bucketId; browserFolderName = item.bucketName
        userRotated = false
        startQueue(window.map { Entry(it.uri.toString(), it.name) }, idx - from)
        closeBrowser()
    }

    private inner class BrowserAdapter : RecyclerView.Adapter<BrowserVH>() {
        var rows: List<VideoItem> = emptyList()
        var scope: List<VideoItem> = emptyList()
        @SuppressLint("NotifyDataSetChanged")
        fun submit(r: List<VideoItem>, sc: List<VideoItem>) { rows = r; scope = sc; notifyDataSetChanged() }
        override fun getItemCount() = rows.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            BrowserVH(ItemBrowserBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun onBindViewHolder(holder: BrowserVH, position: Int) = holder.bind(rows[position])
    }

    private inner class BrowserVH(val v: ItemBrowserBinding) : RecyclerView.ViewHolder(v.root) {
        fun bind(item: VideoItem) {
            val uri = item.uri.toString()
            val current = uri == player.currentMediaItem?.mediaId
            v.title.text = item.name
            v.title.setTypeface(null, if (current) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            val parts = ArrayList<String>()
            if (current) parts += "Now playing"
            if (browserScopeAll && item.bucketName.isNotEmpty()) parts += item.bucketName
            if (item.height > 0) parts += "${item.height}p"
            v.subtitle.text = parts.joinToString(" · ")
            v.durationBadge.isVisible = item.durationMs > 0
            v.durationBadge.text = Fmt.time(item.durationMs)
            v.nowPlaying.isVisible = current
            val pos = Store.position(this@PlayerActivity, uri)
            val dur = item.durationMs.takeIf { it > 0 } ?: Store.duration(this@PlayerActivity, uri)
            v.progress.isVisible = !current && pos > 0 && dur > 0
            if (dur > 0) v.progress.progress = (pos * 1000 / dur).toInt().coerceIn(0, 1000)
            if (current) v.root.setBackgroundResource(R.drawable.bg_current_row)
            else {
                val tv = android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                v.root.setBackgroundResource(tv.resourceId)
            }
            ThumbLoader.load(v.thumb, item)
            v.root.setOnClickListener {
                if (current) closeBrowser() else playFromBrowser(item, browserAdapter.scope)
            }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
