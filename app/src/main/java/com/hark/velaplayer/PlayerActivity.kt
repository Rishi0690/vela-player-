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
import android.view.KeyEvent
import android.widget.ImageView
import androidx.appcompat.widget.TooltipCompat
import android.view.animation.OvershootInterpolator
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
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
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
        /** True when the library sent an already shuffled queue (legacy Shuffle play). Only changes the badge. Prefer [EXTRA_SHUFFLE_PLAY]. */
        const val EXTRA_SHUFFLED = "shuffled"
        /**
         * v2.3: "Shuffle play" without reordering anything. The queue (EXTRA_URIS) is sent in its saved order and the
         * player turns on ExoPlayer's shuffle order for this session only (not saved as the Shuffle button setting).
         * Turning Shuffle off in the player continues in the saved order from the current video. Without EXTRA_INDEX
         * the first video is picked at random.
         */
        const val EXTRA_SHUFFLE_PLAY = "vela_shuffle_play"
        private const val FOCUS_SAVE_MS = 10_000L
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
        private const val PRIVATE_TITLE = FocusTracker.PRIVATE_TITLE
        private const val VAULT_ACTIVITY = "com.hark.velaplayer.VaultActivity"

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

    private enum class Drag { NONE, IGNORE, SEEK, BRIGHTNESS, VOLUME }
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

    // Option panels (subtitles, audio, aspect, player options)
    private lateinit var sheet: PlayerSheet

    // Focus Mode
    private lateinit var focus: FocusTracker
    private var activityStarted = false
    private var nextBreakAtMs = 0L
    private var lastFocusSave = 0L
    private var focusSummaryShowing = false
    private val hideBreakRunnable = Runnable { fadeOut(b.breakCard) }

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
    private val hideTapRunnable = Runnable { fadeOut(b.tapLeft); fadeOut(b.tapRight); seekFbAccum = 0L }

    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!playerReady || intent?.action != ACTION_PIP) return
            when (intent.getIntExtra(EXTRA_CONTROL, 0)) {
                CTRL_PLAY -> togglePlayPauseTo(true)
                CTRL_PAUSE -> togglePlayPauseTo(false)
                CTRL_PREV -> { savePosition(); player.seekToPreviousMediaItem() }
                CTRL_NEXT -> { savePosition(); player.seekToNextMediaItem() }
                CTRL_REWIND -> pipSeek(-1)
                CTRL_FORWARD -> pipSeek(1)
            }
            updatePipParams()
        }
    }
    private val hideUnlockRunnable = Runnable { setUnlockVisible(false) }
    private val ticker = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, if (externalActive || (controlsVisible && player.isPlaying)) 100L else 250L)
        }
    }

    // ---- v2.1 player ----
    /** Where this queue came from (PlayerSession.SOURCE_*). Decides whether Next/Previous follow the library order. */
    private var source = PlayerSession.SOURCE_EXTERNAL
    private var sourceId: String? = null
    private var launchShuffled = false
    /** Bumped on every new queue so a late library lookup never rewrites a newer queue. */
    private var queueGen = 0
    private var sessionFrom = 0
    private var sessionCount = 0
    private var lastSessionTick = 0L
    private var lastOrientation = Configuration.ORIENTATION_UNDEFINED
    /** True while a rotation we requested ourselves (to match the video) is on its way; it must not force Auto. */
    private var autoOrientPending = false
    private val clearAutoOrientRunnable = Runnable { autoOrientPending = false }
    private var tempSpeedActive = false
    private var tempSpeedPrev = 1f
    private var lastDoubleTapAt = 0L
    private var threeFinger = false
    private var threeDone = false
    private var threeStartX = 0f
    private var threeStartY = 0f
    private var scaleBefore = 1f
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private val boost = VolumeBoost()
    private var boostWarned = false

    // ---- v2.2 player ----
    /** Playing Hidden Vault videos (Vault.EXTRA_VAULT): private resume/history, no session/Continue Watching, FLAG_SECURE. */
    private var vaultMode = false
    /** Real names for the on-screen title when the media session only gets "Private video". */
    private val realTitles = HashMap<String, String>()
    /** v2.3: the one bottom-centre message chip (brightness, volume/boost, speed, seek, zoom, modes, notices). */
    private lateinit var chip: PlaybackChip
    /** Latest bottom inset (navigation bar / cutout) for the chip's position when the controls are hidden. */
    private var safeBottom = 0
    private var pipSeekSide = 0
    private var pipSeekAccum = 0L
    private var pipSeekTime = 0L
    private val hidePipSeekRunnable = Runnable { fadeOut(b.p22PipSeek); pipSeekAccum = 0L }
    /** True while the player itself opened another screen (picker, settings, vault): that is not "going to the background". */
    private var openedInAppScreen = false
    private val requestWrite = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) captureFrame() else notice(R.drawable.ic_info, "Storage permission is needed to save screenshots")
    }

    private val pickSubtitle = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadExternalSubtitle(uri)
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this, player = true)
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
            (b.focusPill.layoutParams as? android.widget.FrameLayout.LayoutParams)?.let { it.topMargin = safe.top + dp(14); b.focusPill.layoutParams = it }
            (b.breakCard.layoutParams as? android.widget.FrameLayout.LayoutParams)?.let { it.topMargin = safe.top + dp(64); b.breakCard.layoutParams = it }
            safeBottom = safe.bottom
            if (::chip.isInitialized) chip.reposition()
            (b.speedPanel.layoutParams as? android.widget.FrameLayout.LayoutParams)?.let {
                it.bottomMargin = safe.bottom + dp(92); b.speedPanel.layoutParams = it
            }
            insets
        }
        setFullscreen(true)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    sheet.isOpen -> sheet.dismiss()
                    browserOpen -> closeBrowser()
                    speedPanelOpen -> hideSpeedPanel()
                    locked -> setUnlockVisible(true)
                    focus.active -> endFocus(thenFinish = true)
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
        // v2.2: boost is always on; find out now whether this device can amplify, so the volume bar never offers a fake 200%.
        boost.probe(player.audioSessionId)
        player.shuffleModeEnabled = Prefs.playerShuffle(this)
        player.repeatMode = Prefs.playerRepeat(this)
        lastOrientation = resources.configuration.orientation
        b.videoLayout.mode = aspect
        b.btnAspectLabel.text = aspect.short
        applySubtitleStyle()
        chip = PlaybackChip(b.p23Chip, b.p23ChipIcon, b.p23ChipText, b.p23ChipTrack, b.p23ChipFill) { w, h -> chipPlacement(w, h) }
        sheet = PlayerSheet(b.root) { scheduleHide(); if (fullscreen) setFullscreen(true) }
        // Soft shadows on the white icons of the text buttons too (speed, aspect ratio).
        ShadowIcon.apply(b.btnSpeed, R.drawable.ic_speed)
        ShadowIcon.apply(b.btnAspectLabel, R.drawable.ic_aspect)
        focus = FocusTracker(this)
        setupFocus()
        setupControls()
        setupModes()
        setupGestures()
        setupSpeedPanel()
        setupBrowser()
        if (!pipSupported()) b.btnPip.isVisible = false
        ContextCompat.registerReceiver(this, pipReceiver, IntentFilter(ACTION_PIP), ContextCompat.RECEIVER_NOT_EXPORTED)

        // Recreated by the system (process death / restore from Recents): restore the video paused at its
        // saved position instead of starting playback on its own.
        // A vault player restored after the process died (or after the vault locked) must not play without unlocking again.
        if (intent.getBooleanExtra(Vault.EXTRA_VAULT, false) && savedInstanceState != null && !Vault.isUnlocked()) {
            relockToVault(); return
        }
        if (!loadQueue(intent, autoPlay = savedInstanceState == null)) {
            Toast.makeText(this, "Nothing to play", Toast.LENGTH_SHORT).show()
            finish(); return
        }
        applyOrientation(null)
        handler.post(ticker)
        setControlsVisible(true)
        if (Prefs.focusFeature(this) && Prefs.focusAutoStart(this)) startFocus(announce = false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        savePosition()
        userRotated = false
        loadQueue(intent)
    }

    override fun onResume() {
        super.onResume()
        // Settings may have changed while away (timer, minimal controls, Focus button).
        renderFocusUi()
        if (playerReady) {
            applyModes(); renderModes(); renderRotateButton()
        }
    }

    override fun onPause() {
        super.onPause()
        endTempSpeed()
        if (playerReady) { savePosition(); updateSessionPosition() }
    }

    override fun onStart() {
        super.onStart()
        openedInAppScreen = false
        activityStarted = true
        updateFocusCounting()
        if (Prefs.backgroundAudio(this)) connectSession()
    }

    override fun onStop() {
        super.onStop()
        activityStarted = false
        updateFocusCounting()
        if (!playerReady) return
        savePosition()
        updateSessionPosition()
        val keepPlaying = Prefs.backgroundAudio(this) && player.isPlaying && !isFinishing
        if (!keepPlaying) player.pause()
        // v2.2: leaving the vault player for the background (not PiP, not closing it) locks the vault.
        if (vaultMode && !openedInAppScreen && !isInPipSafe() && !isFinishing && !isChangingConfigurations) Vault.lock()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        // Every startActivity / result launcher ends up here.
        openedInAppScreen = true
        super.startActivityForResult(intent, requestCode, options)
    }

    override fun onRestart() {
        super.onRestart()
        // Back from the background after the vault locked: authenticate again before anything private is shown.
        if (vaultMode && !Vault.isUnlocked()) relockToVault()
    }

    /** Vault is locked: stop, hide the video and send the user through the vault's own authentication. */
    private fun relockToVault() {
        try { b.root.visibility = View.INVISIBLE } catch (_: Exception) { }
        if (playerReady) { try { savePosition(); player.pause(); player.stop() } catch (_: Exception) { } }
        try { startActivity(Intent().setClassName(packageName, VAULT_ACTIVITY)) } catch (_: Exception) { }
        finish()
    }

    override fun onDestroy() {
        if (::focus.isInitialized && focus.active) focus.stop()
        handler.removeCallbacksAndMessages(null)
        if (::chip.isInitialized) chip.release()
        try { unregisterReceiver(pipReceiver) } catch (_: Exception) { }
        if (playerReady) {
            savePosition()
            updateSessionPosition()
            boost.release()
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
        endTempSpeed()
        val o = newConfig.orientation
        if (o != lastOrientation && lastOrientation != Configuration.ORIENTATION_UNDEFINED && o != Configuration.ORIENTATION_UNDEFINED) {
            // A real device rotation (sensor or the rotate button) switches to Auto; our own match-the-video rotation doesn't.
            if (autoOrientPending) { autoOrientPending = false; handler.removeCallbacks(clearAutoOrientRunnable) }
            else if (!isInPipSafe()) applyAutoAspectAfterRotation()
        }
        if (o != Configuration.ORIENTATION_UNDEFINED) lastOrientation = o
        b.videoLayout.resetZoom(false)
        b.videoLayout.requestLayout()
        renderRotateButton()
        chip.reposition()
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
            chip.hideNow()
            b.btnUnlock.isVisible = false
            b.btnUnlockRight.isVisible = false
            if (browserOpen) closeBrowser(false)
            if (speedPanelOpen) hideSpeedPanel()
            if (sheet.isOpen) sheet.dismiss(animate = false)
            b.breakCard.isVisible = false
            b.focusPill.isVisible = false
            b.edgeOverlay.isVisible = false
            b.pipProgress.isVisible = true
            updatePipProgress()
            updatePipParams()
        } else {
            b.edgeOverlay.isVisible = true
            b.pipProgress.isVisible = false
            handler.removeCallbacks(hidePipSeekRunnable); b.p22PipSeek.isVisible = false
            renderFocusUi()
            // PiP window closed (not expanded back): the vault player is gone from the screen, so lock the vault.
            if (vaultMode && !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) Vault.lock()
        }
        b.videoLayout.resetZoom(false)
    }

    // ------------------------------------------------------------------ queue / media

    private fun loadQueue(intent: Intent, autoPlay: Boolean = true): Boolean {
        b.shuffleBadge.isVisible = intent.getBooleanExtra(EXTRA_SHUFFLED, false)
        launchShuffled = intent.getBooleanExtra(EXTRA_SHUFFLED, false)
        setVaultMode(intent.getBooleanExtra(Vault.EXTRA_VAULT, false))
        // Missing extra (other apps, old callers) = keep the given queue, like SOURCE_EXTERNAL.
        source = if (vaultMode) Vault.SOURCE_VAULT else intent.getStringExtra(PlayerSession.EXTRA_SOURCE) ?: PlayerSession.SOURCE_EXTERNAL
        sourceId = if (vaultMode) null else intent.getStringExtra(PlayerSession.EXTRA_SOURCE_ID)
        val uris = intent.getStringArrayListExtra(EXTRA_URIS)
        val titles = intent.getStringArrayListExtra(EXTRA_TITLES)
        val entries = ArrayList<Entry>()
        if (!uris.isNullOrEmpty()) {
            uris.forEachIndexed { i, u -> entries += Entry(u, titles?.getOrNull(i) ?: (Uri.parse(u).lastPathSegment ?: "Video")) }
        } else {
            intent.data?.let { entries += Entry(it.toString(), displayName(it)) }
        }
        if (entries.isEmpty()) return false
        // v2.3: shuffle play = saved order + ExoPlayer shuffle order (the queue itself is never reordered).
        val shufflePlay = intent.getBooleanExtra(EXTRA_SHUFFLE_PLAY, false) && entries.size > 1
        val defaultIndex = if (shufflePlay && !intent.hasExtra(EXTRA_INDEX)) entries.indices.random() else 0
        var index = intent.getIntExtra(EXTRA_INDEX, defaultIndex).coerceIn(0, entries.lastIndex)
        if (!vaultMode) {
            // v2.2: hidden videos never ride along in an ordinary queue (the one explicitly opened still plays).
            val chosen = entries[index]
            val kept = entries.filterIndexed { i, e -> i == index || !Vault.isHidden(this, e.uri) }
            if (kept.size != entries.size) { entries.clear(); entries.addAll(kept); index = entries.indexOf(chosen).coerceAtLeast(0) }
        }
        if (shufflePlay) b.shuffleBadge.isVisible = true
        // The Shuffle button setting applies to every queue; shuffle play turns it on for this queue only.
        player.shuffleModeEnabled = shufflePlay || Prefs.playerShuffle(this)
        startQueue(entries, index, autoPlay)
        // Vault queue = exactly the videos it was given; never the library order.
        if (vaultMode) return true
        // Folder / All videos / Continue Watching (and a lone video) follow the library order for Next/Previous.
        // Playlists, Favorites and shuffled collections keep exactly the queue they were given.
        val libraryOrder = source == PlayerSession.SOURCE_FOLDER || source == PlayerSession.SOURCE_ALL || source == PlayerSession.SOURCE_CONTINUE
        val single = entries.size == 1 && source != PlayerSession.SOURCE_PLAYLIST && source != PlayerSession.SOURCE_FAVORITES
        if (!launchShuffled && (libraryOrder || single)) expandToLibrary()
        return true
    }

    /** Rebuilds the queue around the current video in library order, without interrupting playback. */
    private fun expandToLibrary() {
        if (vaultMode) return
        val id = player.currentMediaItem?.mediaId ?: return
        val gen = queueGen
        try {
            io.execute {
                val w = try { LibraryNavigator.build(this, id) } catch (_: Exception) { null }
                runOnUiThread { if (w != null && playerReady && !isDestroyed && gen == queueGen) applyLibraryWindow(id, w) }
            }
        } catch (_: Exception) { }
    }

    private fun applyLibraryWindow(id: String, w: LibraryNavigator.Window) {
        if (vaultMode || player.currentMediaItem?.mediaId != id) return
        val cur = player.currentMediaItemIndex
        val before = w.items.subList(0, w.index).map { buildItem(Entry(it.uri.toString(), it.name)) }
        val after = w.items.subList(w.index + 1, w.items.size).map { buildItem(Entry(it.uri.toString(), it.name)) }
        // Only the neighbours change; the playing item (and its position, tracks, subtitles) stays untouched.
        if (cur + 1 < player.mediaItemCount) player.removeMediaItems(cur + 1, player.mediaItemCount)
        if (cur > 0) player.removeMediaItems(0, cur)
        if (after.isNotEmpty()) player.addMediaItems(after)
        if (before.isNotEmpty()) player.addMediaItems(0, before)
        resetShuffleOrder()
        updateNavButtons()
        updatePipParams()
        saveSession()
    }

    // ------------------------------------------------------------------ last session (PlayerSession contract)

    /** Saves the queue (a window around the current video), index and position for the library's Resume. */
    private fun saveSession() {
        if (!playerReady || vaultMode) return
        val n = player.mediaItemCount
        if (n == 0) return
        val cur = player.currentMediaItemIndex.coerceIn(0, n - 1)
        val from = (cur - 100).coerceAtLeast(0)
        val to = (cur + 101).coerceAtMost(n)
        val entries = (from until to).map { i ->
            val m = player.getMediaItemAt(i)
            Entry(m.mediaId, titleOf(m))
        }
        sessionFrom = from; sessionCount = entries.size
        PlayerSession.save(this, PlayerSession.Session(entries, cur - from, player.currentPosition.coerceAtLeast(0L),
            // v2.3: entries are always the queue's own (saved) order. `shuffled` only says the library pre-shuffled
            // the queue itself (legacy EXTRA_SHUFFLED); ExoPlayer's shuffle mode is stored separately.
            source, sourceId, launchShuffled, System.currentTimeMillis(), shuffleMode = player.shuffleModeEnabled))
        lastSessionTick = SystemClock.elapsedRealtime()
    }

    private fun updateSessionPosition() {
        if (!playerReady || vaultMode || player.mediaItemCount == 0) return
        val rel = player.currentMediaItemIndex - sessionFrom
        if (sessionCount == 0 || rel < 0 || rel >= sessionCount) { saveSession(); return }
        PlayerSession.updatePosition(this, rel, player.currentPosition.coerceAtLeast(0L))
        lastSessionTick = SystemClock.elapsedRealtime()
    }

    /** Replaces the queue and starts playing [index]. Speed, aspect, subtitle look etc. carry over. */
    private fun startQueue(entries: List<Entry>, index: Int, autoPlay: Boolean = true) {
        endTempSpeed()
        queueGen++
        val start = index.coerceIn(0, entries.lastIndex)
        val startPos = if (Prefs.resume(this)) resumePosition(entries[start].uri) else 0L
        realTitles.clear()
        if (vaultMode) entries.forEach { realTitles[it.uri] = it.title }
        externalSubs = null; externalActive = false; shownExternal = emptyList()
        tracksAppliedFor = null
        b.errorPanel.isVisible = false
        player.setMediaItems(entries.map { buildItem(it) }, start, startPos)
        resetShuffleOrder()
        player.setPlaybackSpeed(Prefs.speed(this))
        applyModes()
        player.prepare()
        player.playWhenReady = autoPlay
        saveSession()
    }

    private fun buildItem(e: Entry): MediaItem {
        val lower = e.uri.lowercase(Locale.ROOT)
        if (vaultMode) realTitles[e.uri] = e.title
        val builder = MediaItem.Builder().setUri(e.uri).setMediaId(e.uri)
            .setMediaMetadata(if (vaultMode) privateMetadata() else MediaMetadata.Builder().setTitle(e.title).build())
        when {
            lower.contains(".m3u8") -> builder.setMimeType(MimeTypes.APPLICATION_M3U8)
            lower.contains(".mpd") -> builder.setMimeType(MimeTypes.APPLICATION_MPD)
        }
        return builder.build()
    }

    /**
     * Vault items: the media session / notification / lock screen only ever see "Private video". Every text field and
     * the artwork are set explicitly because ExoPlayer fills unset fields from the file's own tags (title, artist, cover).
     */
    private fun privateMetadata(): MediaMetadata = MediaMetadata.Builder()
        .setTitle(PRIVATE_TITLE).setDisplayTitle(PRIVATE_TITLE)
        .setArtist("").setAlbumTitle("").setAlbumArtist("").setSubtitle("").setDescription("")
        .setArtworkData(privateArtwork, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
        .build()

    private val privateArtwork: ByteArray by lazy {
        val bmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF202124.toInt()) }
        java.io.ByteArrayOutputStream().use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out); bmp.recycle(); out.toByteArray() }
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
        val d = if (dur == C.TIME_UNSET) 0L else dur
        // Vault videos (and anything hidden meanwhile) keep their history in the vault, never in Continue Watching.
        if (vaultMode || Vault.isHidden(this, item.mediaId)) Vault.savePosition(this, item.mediaId, player.currentPosition, d)
        else Store.savePosition(this, item.mediaId, titleOf(item), player.currentPosition, d)
    }

    private fun resumePosition(uri: String): Long =
        if (vaultMode || Vault.isHidden(this, uri)) Vault.position(this, uri) else Store.position(this, uri)

    private fun markWatched(uri: String) {
        if (vaultMode || Vault.isHidden(this, uri)) Vault.markWatched(this, uri) else Store.markWatched(this, uri)
    }

    /** The real name of [m] (the media session may only carry "Private video" in vault mode). */
    private fun titleOf(m: MediaItem?): String =
        m?.let { realTitles[it.mediaId] } ?: m?.mediaMetadata?.title?.toString() ?: "Video"

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
            updateFocusCounting()
            if (playbackState == Player.STATE_READY && ::focus.isInitialized) focus.updateDuration(player.duration.takeIf { it != C.TIME_UNSET } ?: 0L)
            if (playbackState == Player.STATE_ENDED && ::focus.isInitialized) focus.markCompleted()
            b.loading.isVisible = playbackState == Player.STATE_BUFFERING
            if (playbackState == Player.STATE_READY) b.errorPanel.isVisible = false
            if (playbackState == Player.STATE_ENDED) {
                player.currentMediaItem?.let { markWatched(it.mediaId) }
                setControlsVisible(true)
            }
            updatePlayButton(); updateProgress()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady) endTempSpeed()
            updatePlayButton()
            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) setControlsVisible(true)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updateFocusCounting()
            updatePlayButton()
            if (isPlaying && Prefs.keepScreenOn(this@PlayerActivity)) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (isPlaying) scheduleHide() else { handler.removeCallbacks(hideControlsRunnable); savePosition() }
            updatePipParams()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            b.titleText.text = if (mediaItem == null) "" else titleOf(mediaItem)
            if (::focus.isInitialized) focus.setVideo(mediaItem?.mediaId, if (vaultMode) PRIVATE_TITLE else mediaItem?.mediaMetadata?.title?.toString(), 0L)
            val scheme = mediaItem?.localConfiguration?.uri?.scheme
            isLocalMedia = scheme == "content" || scheme == "file"
            externalSubs = null; externalActive = false; externalName = null; shownExternal = emptyList()
            b.subtitleView.setCues(emptyList())
            videoDecoder = null; audioDecoder = null
            io.execute { retriever?.release(); retriever = null; retrieverUri = null }
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED && mediaItem != null && Prefs.resume(this@PlayerActivity)) {
                val p = resumePosition(mediaItem.mediaId)
                if (p > 0) player.seekTo(p)
            }
            updateNavButtons()
            resetTrackParamsForNewItem()
            updatePipParams()
            if (browserOpen) renderBrowser(false)
            if (mediaItem != null) saveSession()
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) { renderModes(); updateNavButtons(); updatePipParams() }
        override fun onRepeatModeChanged(repeatMode: Int) { applyModes(); renderModes(); updateNavButtons(); updatePipParams() }

        override fun onTracksChanged(tracks: Tracks) {
            val id = player.currentMediaItem?.mediaId ?: return
            if (tracksAppliedFor == id || tracks.groups.isEmpty()) return
            tracksAppliedFor = id
            applyRememberedTracks(tracks)
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                if (::focus.isInitialized) focus.markCompleted() // still attributed to the video that just finished
                oldPosition.mediaItem?.let { markWatched(it.mediaId) }
            }
        }

        override fun onPlayerError(error: PlaybackException) { showError(error) }

        override fun onCues(cueGroup: CueGroup) { if (!externalActive) b.subtitleView.setCues(cueGroup.cues) }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            if (tempSpeedActive) return // temporary long-press speed: the speed button keeps showing the real choice
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
        override fun onAudioSessionIdChanged(eventTime: AnalyticsListener.EventTime, audioSessionId: Int) {
            boost.reattach(audioSessionId)
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
        // In a queue (Play all / playlist), skip a video that can't be opened instead of stopping everything.
        val skippable = e.errorCode in intArrayOf(
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        if (skippable && player.hasNextMediaItem()) {
            val name = titleOf(player.currentMediaItem)
            notice(R.drawable.ic_skip_next, "Skipped \"$name\": ${msg.substringBefore('.')}")
            player.seekToNextMediaItem(); player.prepare(); player.play()
            return
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
        b.btnPlay.setOnClickListener { Motion.bounce(it); togglePlayPause(); scheduleHide() }
        listOf(b.btnRewind, b.btnForward, b.btnPrev, b.btnNext, b.btnUnlock, b.btnUnlockRight).forEach { Motion.pressable(it, 0.88f) }
        b.btnRewind.setOnClickListener { showSeekFeedback(-1, seekBy(-seekIntervalMs)); scheduleHide() }
        b.btnForward.setOnClickListener { showSeekFeedback(1, seekBy(seekIntervalMs)); scheduleHide() }
        b.btnPrev.setOnClickListener { savePosition(); player.seekToPreviousMediaItem(); scheduleHide() }
        b.btnNext.setOnClickListener { savePosition(); player.seekToNextMediaItem(); scheduleHide() }
        b.btnSpeed.setOnClickListener { showSpeedPanel(sliderOnly = false) }
        b.btnSpeed.setOnLongClickListener { showSpeedPanel(sliderOnly = true); true }
        b.btnBrowse.setOnClickListener { openBrowser() }
        b.btnAspectLabel.setOnClickListener { cycleAspect() }
        b.btnAspectLabel.setOnLongClickListener { showAspectDialog(); true }
        b.btnSubtitle.setOnClickListener { showSubtitleDialog() }
        b.btnAudio.setOnClickListener { showAudioDialog() }
        b.btnMore.setOnClickListener { showMoreMenu(it) }
        b.btnFocus.setOnClickListener { if (focus.active) endFocus(thenFinish = false) else startFocus(announce = true) }
        b.btnLock.setOnClickListener { lockControls() }
        b.btnLockRight.setOnClickListener { lockControls() }
        b.btnUnlock.setOnClickListener { unlockControls() }
        b.btnUnlockRight.setOnClickListener { unlockControls() }
        b.btnRotate.setOnClickListener { v ->
            // Small turn of the icon toward the new orientation, then it settles on the new state.
            v.animate().cancel()
            v.animate().rotation(if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) -90f else 90f)
                .setDuration(260).setInterpolator(OvershootInterpolator(1.6f))
                .withEndAction { v.rotation = 0f; renderRotateButton() }.start()
            rotateScreen(); scheduleHide()
        }
        b.btnRotate.setOnLongClickListener {
            // Back to automatic: follow the video (or the orientation chosen in Settings).
            userRotated = false
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            applyOrientation(player.videoSize.takeIf { it.width > 0 && it.height > 0 })
            renderRotateButton()
            showIndicator(R.drawable.ic_screen_rotation, "Rotation follows the video", null); hideIndicatorSoon()
            true
        }
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
                seekDragFrom = player.currentPosition
                handler.removeCallbacks(hideControlsRunnable)
                drag = Drag.IGNORE // a timeline drag never turns into a screen gesture
                showSeekPreview(sb.progress.toLong())
                b.seekPreview.alpha = 0f; b.seekPreview.scaleX = 0.9f; b.seekPreview.scaleY = 0.9f
                b.seekPreview.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160).setInterpolator(Motion.emphasized).start()
            }
            override fun onStopTrackingTouch(sb: SeekBar) {
                player.seekTo(sb.progress.toLong())
                userSeeking = false
                drag = Drag.NONE
                b.seekPreview.animate().alpha(0f).setDuration(160).withEndAction { b.seekPreview.isVisible = false; b.seekPreview.alpha = 1f }.start()
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
            else -> (if (side < 0) "−" else "+") + secs + "s" // v2.3: compact chip, same direction + seconds
        }
        box.contentDescription = if (secs == 0L) text.text else (if (side < 0) "Rewound " else "Forwarded ") + secs + if (secs == 1L) " second" else " seconds"
        fadeIn(box)
        handler.postDelayed(hideTapRunnable, 900)
    }

    private fun fadeIn(v: View) {
        v.animate().cancel()
        if (!v.isVisible) { v.alpha = 0f; v.scaleX = 0.92f; v.scaleY = 0.92f; v.isVisible = true }
        v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200).setInterpolator(Motion.emphasized).start()
    }

    private fun fadeOut(v: View) {
        if (!v.isVisible) return
        v.animate().cancel()
        v.animate().alpha(0f).setDuration(220).setInterpolator(Motion.emphasizedAccel).withEndAction { v.isVisible = false }.start()
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
        if (playerReady && player.isPlaying && SystemClock.elapsedRealtime() - lastSessionTick >= 5_000L) {
            updateSessionPosition()
            savePosition() // Store resume position too, so a killed process loses at most a few seconds
        }
        if (controlsVisible) updateProgress()
        if (::focus.isInitialized && focus.active) focusTick()
        if (b.pipProgress.isVisible) updatePipProgress()
        if (externalActive) renderExternalSubs()
    }

    private fun setControlsVisible(visible: Boolean) {
        if (visible && locked) return
        if (visible && Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode) return
        controlsVisible = visible
        b.controls.animate().cancel()
        val slide = dp(28).toFloat()
        listOf(b.topBar, b.bottomBar, b.centerControls).forEach { it.animate().cancel() }
        // Focus counter follows the controls (the session keeps counting while it's hidden).
        if (visible && focusPillWanted()) {
            b.focusText.text = "Focus ${FocusFmt.clock(focus.elapsedMs())}"
            b.focusPill.translationY = dp(52).toFloat()
            fadeIn(b.focusPill)
        } else if (!visible) fadeOut(b.focusPill)
        if (visible) {
            // Top bar drops in, bottom bar rises, centre buttons pop — all on one soft curve.
            if (!b.controls.isVisible) {
                b.controls.alpha = 0f
                b.topBar.translationY = -slide; b.bottomBar.translationY = slide
                b.centerControls.scaleX = 0.88f; b.centerControls.scaleY = 0.88f
            }
            b.controls.isVisible = true
            b.controls.animate().alpha(1f).setDuration(220).setInterpolator(Motion.emphasized).start()
            b.topBar.animate().translationY(0f).setDuration(340).setInterpolator(Motion.emphasized).start()
            b.bottomBar.animate().translationY(0f).setDuration(340).setInterpolator(Motion.emphasized).start()
            b.centerControls.animate().scaleX(1f).scaleY(1f).setDuration(340).setInterpolator(Motion.emphasized).start()
            updateProgress(); updatePlayButton(); updateNavButtons()
            scheduleHide()
        } else {
            handler.removeCallbacks(hideControlsRunnable)
            b.seekPreview.isVisible = false
            b.topBar.animate().translationY(-slide).setDuration(220).setInterpolator(Motion.emphasizedAccel).start()
            b.bottomBar.animate().translationY(slide).setDuration(220).setInterpolator(Motion.emphasizedAccel).start()
            b.centerControls.animate().scaleX(0.92f).scaleY(0.92f).setDuration(220).setInterpolator(Motion.emphasizedAccel).start()
            b.controls.animate().alpha(0f).setDuration(220).setInterpolator(Motion.emphasizedAccel).withEndAction {
                if (!controlsVisible) b.controls.isVisible = false
            }.start()
        }
        if (::chip.isInitialized) chip.reposition() // v2.3: below the timeline while the controls show, small at the bottom edge otherwise
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideControlsRunnable)
        val sheetOpen = ::sheet.isInitialized && sheet.isOpen
        val focusOn = ::focus.isInitialized && focus.active
        if (controlsVisible && player.isPlaying && !userSeeking && !sheetOpen)
            handler.postDelayed(hideControlsRunnable, if (focusOn && Prefs.focusMinimal(this)) 2200 else 3500)
    }

    private fun lockControls() {
        setControlsVisible(false)
        if (speedPanelOpen) hideSpeedPanel()
        if (browserOpen) closeBrowser(false)
        locked = true
        setUnlockVisible(true) // v2.2: no toast; the unlock buttons themselves show what happened
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
        requestOrientationProgrammatic(when {
            pref == "landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            pref == "portrait" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            pref == "sensor" -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            vs != null -> orientationFor(vs)
            else -> return
        })
    }

    /**
     * Orientation changes the player makes by itself (to match the video / Settings) are not "the user rotated
     * the device", so they must not switch the aspect ratio to Auto. Mark the rotation we expect.
     */
    private fun requestOrientationProgrammatic(req: Int) {
        val landscapeNow = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val wantsLand = req == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE || req == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        val wantsPort = req == ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT || req == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        if ((wantsLand && !landscapeNow) || (wantsPort && landscapeNow)) {
            autoOrientPending = true
            handler.removeCallbacks(clearAutoOrientRunnable)
            handler.postDelayed(clearAutoOrientRunnable, 1500)
        }
        if (requestedOrientation != req) requestedOrientation = req
        renderRotateButton()
    }

    /** Device rotated: Auto aspect for the new shape, and drop any manual pinch zoom / pan. Not saved as the user's choice. */
    private fun applyAutoAspectAfterRotation() {
        aspect = AspectMode.AUTO
        b.videoLayout.mode = AspectMode.AUTO // also resets the manual zoom/pan transform
        b.btnAspectLabel.text = aspect.short
    }

    /** Rotation button: icon shows whether rotation follows the video (auto) or is locked by the user, and to which side. */
    private fun renderRotateButton() {
        if (!::b.isInitialized) return
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val btn = b.btnRotate
        val icon = when {
            !userRotated -> R.drawable.ic_screen_rotation
            landscape -> R.drawable.ic_screen_lock_landscape
            else -> R.drawable.ic_screen_lock_portrait
        }
        if (btn.tag != icon) { btn.setImageResource(icon); btn.tag = icon }
        btn.imageTintList = android.content.res.ColorStateList.valueOf(if (userRotated) ThemeManager.primary(this) else 0xFFFFFFFF.toInt())
        btn.contentDescription = if (userRotated)
            "Screen locked to ${if (landscape) "landscape" else "portrait"}. Tap to rotate, press and hold to follow the video again"
        else "Rotate screen to ${if (landscape) "portrait" else "landscape"}"
    }

    private fun orientationFor(vs: VideoSize): Int =
        if (vs.width * vs.pixelWidthHeightRatio >= vs.height) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT

    private fun rotateScreen() {
        userRotated = true
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        renderRotateButton()
    }

    // ------------------------------------------------------------------ aspect ratio

    private fun setAspect(m: AspectMode) {
        aspect = m
        b.videoLayout.mode = m          // resizes the video surface; player keeps running
        b.btnAspectLabel.text = m.short
        Prefs.saveAspect(this, m)
        if (m == AspectMode.AUTO) {
            val vs = player.videoSize
            if (vs.width > 0 && vs.height > 0) { userRotated = false; requestOrientationProgrammatic(orientationFor(vs)) }
        }
        // v2.2: no on-screen message; the aspect button label already shows the new mode.
        b.btnAspectLabel.contentDescription = "Aspect ratio: ${m.label}"
        scheduleHide()
    }

    private fun cycleAspect() {
        val all = AspectMode.values()
        setAspect(all[(aspect.ordinal + 1) % all.size])
    }

    private fun showAspectDialog() {
        handler.removeCallbacks(hideControlsRunnable)
        val acts = ArrayList<PlayerSheet.Action>()
        if (b.videoLayout.isZoomed) acts += PlayerSheet.Action("Reset zoom", R.drawable.ic_zoom) { b.videoLayout.resetZoom() }
        sheet.show("Aspect ratio", AspectMode.values().map { m ->
            PlayerSheet.Option(m.label, aspectHint(m), selected = m == aspect) { setAspect(m) }
        }, acts, footer = "Tip: tap the aspect button to cycle, pinch the video to zoom and drag with two fingers to move it. Rotating the phone switches to Auto.")
    }

    private fun aspectHint(m: AspectMode): String? = when (m) {
        AspectMode.FIT -> "Whole picture, black bars if needed"
        AspectMode.STRETCH -> "Fills the screen, may distort"
        AspectMode.ZOOM -> "Fills the screen, crops the edges"
        AspectMode.FILL -> "Fills width or height"
        AspectMode.ORIGINAL -> "Real pixel size"
        AspectMode.AUTO -> "Uses the video's own ratio and rotates to match"
        else -> null
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
                lastDoubleTapAt = SystemClock.uptimeMillis()
                if (!locked) handleDoubleTap(e.x)
                return true
            }
            override fun onLongPress(e: MotionEvent) {
                // Press and hold = temporary speed. GestureDetector already cancels this when the finger moves past
                // the touch slop or a second finger lands, so it never fights swipes, pinch or the 3-finger gesture.
                if (locked || scaling || threeFinger || drag != Drag.NONE || speedPanelOpen || sheet.isOpen) return
                if (SystemClock.uptimeMillis() - lastDoubleTapAt < 500) return
                if (!player.isPlaying) return
                startTempSpeed()
            }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (locked || scaling || e1 == null || e2.pointerCount > 1) return false
                handleScroll(e1, e2, distanceX, distanceY)
                return true
            }
        })
        gestures.setIsLongpressEnabled(true)

        scaleGestures = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                if (locked || !Prefs.gesture(this@PlayerActivity, "gesture_pinch_zoom")) return false
                if (threeFinger) return false
                scaling = true
                endTempSpeed()
                if (drag == Drag.SEEK) userSeeking = false
                if (drag == Drag.BRIGHTNESS || drag == Drag.VOLUME) chip.hideSoon(400)
                drag = Drag.IGNORE
                scaleBefore = b.videoLayout.userScale
                lastFocusX = detector.focusX; lastFocusY = detector.focusY
                return true
            }
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                b.videoLayout.setUserScale(b.videoLayout.userScale * detector.scaleFactor)
                // Two-finger drag moves the zoomed picture; single-finger swipes stay brightness/volume/seek.
                b.videoLayout.panBy(detector.focusX - lastFocusX, detector.focusY - lastFocusY)
                lastFocusX = detector.focusX; lastFocusY = detector.focusY
                showIndicator(R.drawable.ic_zoom, "Zoom ${(b.videoLayout.userScale * 100).roundToInt()}%", null, sticky = true)
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
            // Hold-for-speed only while playing, so a slow hold-then-swipe still works on a paused video.
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) gestures.setIsLongpressEnabled(player.isPlaying && !locked && !speedPanelOpen)
            handleThreeFinger(ev)
            // Once three fingers are down this touch belongs to the screenshot gesture only.
            if (!threeFinger) {
                scaleGestures.onTouchEvent(ev)
                gestures.onTouchEvent(ev)
            }
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                endDrag()
                endTempSpeed()
                threeFinger = false; threeDone = false
            }
            true
        }
    }

    private fun avgX(ev: MotionEvent): Float { var s = 0f; for (i in 0 until ev.pointerCount) s += ev.getX(i); return s / ev.pointerCount }
    private fun avgY(ev: MotionEvent): Float { var s = 0f; for (i in 0 until ev.pointerCount) s += ev.getY(i); return s / ev.pointerCount }

    /** Deliberate three-finger swipe down = save the current video frame (only when enabled in Settings). */
    private fun handleThreeFinger(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (ev.pointerCount == 3 && !threeFinger && !locked && Prefs.threeFingerScreenshot(this)) {
                    threeFinger = true; threeDone = false
                    // Stop whatever this touch had started (pinch, swipe, hold) without applying it.
                    val wasScaling = scaling
                    val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                    scaleGestures.onTouchEvent(cancel); gestures.onTouchEvent(cancel); cancel.recycle()
                    if (wasScaling) { if (scaleBefore < 1.05f) b.videoLayout.resetZoom(false) else b.videoLayout.setUserScale(scaleBefore) }
                    if (drag == Drag.SEEK) { userSeeking = false; chip.hide() }
                    if (drag == Drag.BRIGHTNESS || drag == Drag.VOLUME) chip.hideSoon(300)
                    drag = Drag.IGNORE
                    scaling = true // blocks a leftover single finger from starting a swipe in this touch
                    endTempSpeed()
                    threeStartX = avgX(ev); threeStartY = avgY(ev)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (threeFinger && !threeDone && ev.pointerCount >= 3) {
                    val dy = avgY(ev) - threeStartY
                    val dx = avgX(ev) - threeStartX
                    if (dy > dp(90) && abs(dx) < dy * 0.6f) { threeDone = true; captureFrame() }
                }
            }
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
            togglePlayPause() // v2.2: no on-screen message
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
            // Zoomed or stretched video doesn't change what a single finger does (panning is a two-finger drag).
            drag = when {
                abs(tdx) > abs(tdy) * 1.2f ->
                    if (Prefs.gesture(this, "gesture_horizontal_seek") && player.isCurrentMediaItemSeekable && player.duration > 0) Drag.SEEK else Drag.IGNORE
                abs(tdy) > abs(tdx) * 1.2f -> verticalDragFor(e1.x, w)
                else -> return
            }
            when (drag) {
                Drag.SEEK -> { seekStart = player.currentPosition; seekTarget = seekStart; userSeeking = true }
                Drag.VOLUME -> volumeLevel = currentVolumeLevel()
                Drag.BRIGHTNESS -> brightness = currentBrightness()
                else -> {}
            }
        }
        when (drag) {
            Drag.SEEK -> {
                val dur = player.duration
                val span = min(dur, 120_000L).coerceAtLeast(30_000L) // full screen width = up to 2 minutes
                seekTarget = (seekStart + ((e2.x - e1.x) / w * span).toLong()).coerceIn(0L, dur)
                val diff = (seekTarget - seekStart) / 1000
                showIndicator(if (diff >= 0) R.drawable.ic_fast_forward else R.drawable.ic_rewind,
                    "${Fmt.time(seekTarget)}  ·  ${if (diff >= 0) "+" else "−"}${abs(diff)}s", null, sticky = true)
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
                val upper = if (boostAvailable()) max * 2f else max.toFloat()
                val prev = volumeLevel
                volumeLevel = (volumeLevel + dy / h * max * 1.5f).coerceIn(0f, upper)
                applyVolumeLevel(prev, max)
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
            Drag.BRIGHTNESS, Drag.VOLUME -> chip.hideSoon(800)
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

    /**
     * v2.3: brightness/volume level in the shared bottom chip (icon, percentage and a thin level bar).
     * The boost range (101–200%) stays visibly different: accent-tinted, "Boost" label, and the bar shows only the
     * extra amplification. Stays up while swiping; hidden shortly after the finger lifts.
     */
    private fun showSideLevel(icon: Int, pct: Int, boosted: Boolean = false) {
        if (boosted) chip.show(icon, "Boost $pct%", level = pct - 100, accent = ThemeManager.primary(this), sticky = true)
        else chip.show(icon, "$pct%", level = pct, sticky = true)
    }

    private fun currentBrightness(): Float {
        val wb = window.attributes.screenBrightness
        if (wb >= 0f) return wb
        return try { Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f } catch (e: Exception) { 0.5f }
    }

    /** v2.3: every temporary playback message goes to the shared bottom chip (no centre-screen box any more). */
    private fun showIndicator(icon: Int, text: String, progress: Int?, sticky: Boolean = false) {
        if (isInPipSafe()) return
        chip.show(icon, text, level = progress, sticky = sticky)
    }

    private fun hideIndicatorSoon() = chip.hideSoon(700)

    /** Short notice or error that used to be a toast; same chip, shown a little longer. Falls back to a toast in PiP. */
    private fun notice(icon: Int, text: String) {
        if (isInPipSafe() || !::chip.isInitialized) { toast(text); return }
        chip.show(icon, text, durationMs = PlaybackChip.LONG)
    }

    // ------------------------------------------------------------------ seek preview

    private var seekDragFrom = 0L

    private fun showSeekPreview(posMs: Long) {
        val diff = (posMs - (if (userSeeking && drag != Drag.SEEK) seekDragFrom else seekStart)) / 1000
        b.seekPreviewTime.text = if (abs(diff) >= 1) "${Fmt.time(posMs)}   ${if (diff > 0) "+" else "−"}${Fmt.time(abs(diff) * 1000)}" else Fmt.time(posMs)
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
                chip.chipStrokeColor = android.content.res.ColorStateList.valueOf(ThemeManager.primary(this))
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
        if (sheet.isOpen) sheet.dismiss(animate = false, notify = false)
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

    /** Track name split into a readable title (label + full language name) and details (channels, codec). */
    private fun trackTexts(f: Format, n: Int, type: Int): Pair<String, String?> {
        val title = ArrayList<String>()
        f.label?.takeIf { it.isNotBlank() }?.let { title += it }
        f.language?.takeIf { it.isNotBlank() && it != "und" }?.let {
            val name = Locale.forLanguageTag(it).displayName
            if (name.isNotBlank() && title.none { p -> p.equals(name, true) }) title += name
        }
        val details = ArrayList<String>()
        if (type == C.TRACK_TYPE_AUDIO) {
            when (f.channelCount) {
                1 -> details += "Mono"; 2 -> details += "Stereo"; 6 -> details += "5.1"; 8 -> details += "7.1"
                Format.NO_VALUE -> {}
                else -> details += "${f.channelCount} channels"
            }
            codecName(f.sampleMimeType)?.let { details += it }
            if (f.bitrate > 0) details += "${f.bitrate / 1000} kbps"
        } else codecName(f.sampleMimeType ?: f.containerMimeType)?.let { details += it }
        val t = if (title.isEmpty()) "Track $n" else title.joinToString(" · ")
        return t to (listOf("Track $n") + details).joinToString(" · ")
    }

    private fun showAudioDialog() {
        handler.removeCallbacks(hideControlsRunnable)
        val opts = ArrayList<PlayerSheet.Option>()
        var n = 0
        player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.forEach { g ->
            for (i in 0 until g.length) if (g.isTrackSupported(i)) {
                n++
                val f = g.getTrackFormat(i)
                val (title, sub) = trackTexts(f, n, C.TRACK_TYPE_AUDIO)
                opts += PlayerSheet.Option(title, sub, selected = g.isTrackSelected(i)) {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                        .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
                        .build()
                    Prefs.saveAudioLang(this, f.language) // v2.3: no on-screen message
                }
            }
        }
        val muted = player.volume == 0f
        val acts = listOf(PlayerSheet.Action(if (muted) "Unmute" else "Mute", if (muted) R.drawable.ic_volume else R.drawable.ic_volume_off) {
            player.volume = if (muted) 1f else 0f
            showIndicator(if (muted) R.drawable.ic_volume else R.drawable.ic_volume_off, if (muted) "Sound on" else "Muted", null)
            hideIndicatorSoon()
        })
        sheet.show("Audio", opts, acts, footer = if (opts.isEmpty()) "No playable audio tracks in this video." else null)
    }

    private sealed class SubOption {
        object Off : SubOption()
        data class Embedded(val group: Tracks.Group, val index: Int) : SubOption()
        object External : SubOption()
    }

    private fun showSubtitleDialog() {
        handler.removeCallbacks(hideControlsRunnable)
        val textDisabled = player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        val opts = ArrayList<PlayerSheet.Option>()
        var anySelected = false
        val embedded = ArrayList<PlayerSheet.Option>()
        var n = 0
        player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.forEach { g ->
            for (i in 0 until g.length) if (g.isTrackSupported(i)) {
                n++
                val (title, sub) = trackTexts(g.getTrackFormat(i), n, C.TRACK_TYPE_TEXT)
                val sel = !externalActive && !textDisabled && g.isTrackSelected(i)
                if (sel) anySelected = true
                embedded += PlayerSheet.Option(title, sub, selected = sel) {
                    selectSubtitle(SubOption.Embedded(g, i)) // v2.3: no on-screen message
                }
            }
        }
        opts += PlayerSheet.Option("Off", null, selected = !externalActive && !anySelected) {
            selectSubtitle(SubOption.Off)
        }
        opts += embedded
        if (externalSubs != null) opts += PlayerSheet.Option(externalName ?: "Subtitle file", "Loaded from file", selected = externalActive) {
            selectSubtitle(SubOption.External)
        }
        sheet.show("Subtitles", opts, listOf(
            PlayerSheet.Action("Load file", R.drawable.ic_folder) { pickSubtitle.launch(arrayOf("*/*")) },
            PlayerSheet.Action("Style & timing", R.drawable.ic_tune) { showSubtitleStyleDialog() }
        ))
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
                        result == null -> notice(R.drawable.ic_subtitles, "Couldn't open \"$name\"")
                        result.isEmpty() -> notice(R.drawable.ic_subtitles, "No subtitle lines found in \"$name\"")
                        else -> {
                            externalSubs = result; externalName = name; subtitleDelayMs = Prefs.subDelay(this)
                            activateExternal() // v2.3: no on-screen message when subtitles change
                        }
                    }
                }
            }
            "ass", "ssa", "ttml", "dfxp", "xml" -> sideloadSubtitle(uri, name, ext)
            else -> notice(R.drawable.ic_subtitles, "Unsupported subtitle format \".$ext\" (use SRT, VTT, ASS/SSA or TTML)")
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
        player.seekTo(idx, pos) // v2.3: no on-screen message when subtitles change
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

    private fun showMoreMenu(@Suppress("UNUSED_PARAMETER") anchor: View) {
        handler.removeCallbacks(hideControlsRunnable)
        val o = ArrayList<PlayerSheet.Option>()
        o += PlayerSheet.Option("Playback speed", null, icon = R.drawable.ic_speed, trailing = formatSpeed(player.playbackParameters.speed)) { showSpeedDialog() }
        if (Prefs.focusFeature(this)) o += PlayerSheet.Option("Focus Mode", if (focus.active) "Focused ${FocusFmt.clock(focus.elapsedMs())}" else "Track real watching time, fewer distractions",
            icon = R.drawable.ic_focus, trailing = if (focus.active) "On" else "Off") { if (focus.active) endFocus(false) else startFocus(true) }
        o += PlayerSheet.Option("Focus stats", null, icon = R.drawable.ic_insights) { startActivity(Intent(this, FocusActivity::class.java)) }
        o += PlayerSheet.Option("Load subtitle file", null, icon = R.drawable.ic_subtitles) { pickSubtitle.launch(arrayOf("*/*")) }
        o += PlayerSheet.Option("Video info", null, icon = R.drawable.ic_info) { showInfoDialog() }
        if (pipSupported()) o += PlayerSheet.Option("Picture-in-picture", null, icon = R.drawable.ic_pip) { enterPip() }
        if (!vaultMode) o += PlayerSheet.Option("Browse videos", null, icon = R.drawable.ic_video_library) { openBrowser() }
        o += PlayerSheet.Option("Background audio", "Keep playing when you leave the player", icon = R.drawable.ic_audiotrack,
            trailing = if (Prefs.backgroundAudio(this)) "On" else "Off") {
            val on = !Prefs.backgroundAudio(this)
            Prefs.sp(this).edit().putBoolean("background_audio", on).apply()
            if (on) connectSession() else disconnectSession()
            showIndicator(R.drawable.ic_audiotrack, if (on) "Background audio on" else "Background audio off", null)
        }
        o += PlayerSheet.Option("All settings", null, icon = R.drawable.ic_tune) { startActivity(Intent(this, SettingsActivity::class.java)) }
        sheet.show("Player options", o, radio = false)
    }

    // ------------------------------------------------------------------ Focus Mode

    private fun setupFocus() {
        b.btnFocus.isVisible = Prefs.focusFeature(this)
        b.breakKeep.setOnClickListener { handler.removeCallbacks(hideBreakRunnable); fadeOut(b.breakCard) }
        b.breakPause.setOnClickListener {
            handler.removeCallbacks(hideBreakRunnable); fadeOut(b.breakCard)
            player.pause()
            showIndicator(R.drawable.ic_pause, "Paused for a break", null); hideIndicatorSoon()
        }
        renderFocusUi()
    }

    private fun startFocus(announce: Boolean) {
        if (focus.active) return
        focus.start()
        val item = player.currentMediaItem
        focus.setVideo(item?.mediaId, if (vaultMode) PRIVATE_TITLE else item?.mediaMetadata?.title?.toString(), player.duration.takeIf { it != C.TIME_UNSET } ?: 0L)
        nextBreakAtMs = Prefs.focusBreakMs(this)
        lastFocusSave = android.os.SystemClock.elapsedRealtime()
        focusTodayBase = FocusStore.get(this).today()
        updateFocusCounting()
        renderFocusUi()
        // v2.3: no on-screen message; the Focus button turns accent-coloured and the timer pill shows the session.
    }

    /** Ends the session, shows the summary (if enabled), and optionally closes the player after. */
    private fun endFocus(thenFinish: Boolean) {
        if (!focus.active) { if (thenFinish) finish(); return }
        val id = focus.stop()
        renderFocusUi()
        val summary = if (id >= 0) FocusStore.get(this).sessionSummary(id) else null
        if (summary == null || summary.focusMs < 1000 || !Prefs.focusSummary(this) || isInPipSafe()) {
            if (thenFinish) finish()
            return
        }
        showFocusSummary(summary, thenFinish)
    }

    private fun isInPipSafe() = Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode

    private fun showFocusSummary(sm: FocusStore.Summary, thenFinish: Boolean) {
        val store = FocusStore.get(this)
        val today = store.today()
        val goal = Prefs.focusGoalMs(this)
        val lines = ArrayList<String>()
        lines += "You focused for ${FocusFmt.dur(sm.focusMs)} of real watching time."
        if (sm.videos > 0) lines += "Videos watched: ${sm.videos}" + if (sm.completed > 0) " (${sm.completed} finished)" else ""
        lines += if (goal > 0) "Today: ${FocusFmt.dur(today)} of your ${FocusFmt.dur(goal)} goal" + (if (today >= goal) " — goal reached!" else "")
        else "Today: ${FocusFmt.dur(today)} in total"
        focusSummaryShowing = true
        handler.removeCallbacks(hideControlsRunnable)
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Focus session complete")
            .setIcon(R.drawable.ic_focus)
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton(if (thenFinish) "Done" else "OK") { _, _ -> }
            .setNeutralButton("View stats") { _, _ -> startActivity(Intent(this, FocusActivity::class.java)) }
            .setOnDismissListener { focusSummaryShowing = false; if (thenFinish) finish() else { scheduleHide(); if (fullscreen) setFullscreen(true) } }
            .show()
    }

    /** Counts only while focus is on, the video is genuinely playing, and the player is visible (incl. PiP). */
    private fun updateFocusCounting() {
        if (!::focus.isInitialized) return
        focus.setCounting(focus.active && activityStarted && playerReady && player.isPlaying)
    }

    private fun focusTick() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastFocusSave >= FOCUS_SAVE_MS) { focus.flush(); lastFocusSave = now; focusTodayBase = FocusStore.get(this).today() }
        val elapsed = focus.elapsedMs()
        if (b.focusPill.isVisible) {
            b.focusText.text = "Focus ${FocusFmt.clock(elapsed)}"
            val goal = Prefs.focusGoalMs(this)
            if (goal > 0) {
                val today = focusTodayBase + focus.unsavedMs()
                b.focusGoal.setProgressCompat(((today.toFloat() / goal) * 1000).toInt().coerceIn(0, 1000), true)
                val key = FocusStore.dayKey(System.currentTimeMillis())
                if (today >= goal && Prefs.sp(this).getString("focus_goal_done_day", "") != key) {
                    Prefs.sp(this).edit().putString("focus_goal_done_day", key).apply()
                    showIndicator(R.drawable.ic_check_circle, "Daily focus goal reached!", null); hideIndicatorSoon()
                }
            }
        }
        val breakMs = Prefs.focusBreakMs(this)
        if (breakMs > 0 && nextBreakAtMs <= 0) nextBreakAtMs = (elapsed / breakMs + 1) * breakMs // turned on mid-session
        if (breakMs <= 0) nextBreakAtMs = 0
        if (breakMs > 0 && elapsed >= nextBreakAtMs && nextBreakAtMs > 0) {
            nextBreakAtMs = (elapsed / breakMs + 1) * breakMs
            if (!isInPipSafe() && !locked) {
                b.breakText.text = "You've been focusing for ${FocusFmt.dur(elapsed)}. A short break helps you remember more."
                fadeIn(b.breakCard)
                handler.removeCallbacks(hideBreakRunnable)
                handler.postDelayed(hideBreakRunnable, 15_000)
            }
        }
    }

    /** Today's saved focus total (refreshed on each save, so the timer doesn't query the database every frame). */
    private var focusTodayBase = 0L

    private fun renderFocusUi() {
        if (!::focus.isInitialized) return
        val on = focus.active
        b.btnFocus.isVisible = Prefs.focusFeature(this)
        b.btnFocus.alpha = if (on) 1f else 0.85f
        b.btnFocus.imageTintList = android.content.res.ColorStateList.valueOf(if (on) ThemeManager.primary(this) else 0xFFFFFFFF.toInt())
        b.btnFocus.contentDescription = if (on) "Focus Mode on. Tap to end the session" else "Start Focus Mode"
        // The counter only shows together with the other controls; the session keeps counting either way.
        val showPill = focusPillWanted() && controlsVisible && b.controls.isVisible
        if (showPill && !b.focusPill.isVisible) { b.focusPill.translationY = dp(52).toFloat(); fadeIn(b.focusPill) }
        else if (!showPill) { b.focusPill.animate().cancel(); b.focusPill.isVisible = false }
        b.focusGoal.isVisible = on && Prefs.focusGoalMs(this) > 0
        if (on) b.focusText.text = "Focus ${FocusFmt.clock(focus.elapsedMs())}"
        // Minimal controls: hide only the distracting extra (quick video browser). PiP, rotation and lock stay
        // available in Focus Mode — they are watching tools, not distractions.
        val minimal = on && Prefs.focusMinimal(this)
        b.btnBrowse.isVisible = !minimal && !vaultMode // the library browser never opens over vault playback
        b.btnLockRight.isVisible = true
        b.btnRotate.isVisible = true
        b.btnPip.isVisible = pipSupported()
        if (!on) { handler.removeCallbacks(hideBreakRunnable); b.breakCard.isVisible = false }
    }

    private fun focusPillWanted(): Boolean =
        ::focus.isInitialized && focus.active && Prefs.focusShowTimer(this) && !isInPipSafe()

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
        // Not seekable (live streams): rewind/forward stay visible but disabled.
        val playing = player.playWhenReady && player.playbackState != Player.STATE_ENDED && player.playbackState != Player.STATE_IDLE
        val secs = seekIntervalMs / 1000
        val play = if (playing) pipAction(R.drawable.ic_pip_pause, "Pause", CTRL_PAUSE) else pipAction(R.drawable.ic_pip_play, "Play", CTRL_PLAY)
        val prev = pipAction(R.drawable.ic_pip_skip_prev, "Previous video", CTRL_PREV, player.hasPreviousMediaItem())
        val next = pipAction(R.drawable.ic_pip_skip_next, "Next video", CTRL_NEXT, player.hasNextMediaItem())
        val seekable = player.isCurrentMediaItemSeekable && player.duration != C.TIME_UNSET && !player.isCurrentMediaItemLive
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
        if (!pipSupported() || Build.VERSION.SDK_INT < 26) { notice(R.drawable.ic_pip, "Picture-in-picture is off or not available"); return }
        setControlsVisible(false)
        try { pipParams()?.let { enterPictureInPictureMode(it) } } catch (e: Exception) { notice(R.drawable.ic_pip, "Couldn't start picture-in-picture") }
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
        VelaScroller.attach(b.browserList)
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
        if (browserOpen || vaultMode) return
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
            val hidden = Vault.hiddenSet(this)
            val items = VideoRepository.load(this).filter { it.uri.toString() !in hidden }
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
        source = if (browserScopeAll) PlayerSession.SOURCE_ALL else PlayerSession.SOURCE_FOLDER
        sourceId = if (browserScopeAll) null else item.bucketId
        launchShuffled = false; b.shuffleBadge.isVisible = false
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

    // ------------------------------------------------------------------ v2.1: playback modes

    private fun setupModes() {
        listOf(b.btnShuffle, b.btnLoop, b.btnAutoNext, b.btnRotate).forEach { Motion.pressable(it, 0.86f) }
        b.btnShuffle.setOnClickListener { v ->
            val on = !player.shuffleModeEnabled
            player.shuffleModeEnabled = on // ExoPlayer plays a shuffled order; the queue's own order stays intact
            if (on) resetShuffleOrder() // every other video comes after the current one, nothing is skipped
            Prefs.setPlayerShuffle(this, on)
            Motion.haptic(v)
            modeFeedback(R.drawable.ic_shuffle, if (on) "Shuffle on" else "Shuffle off")
            saveSession()
        }
        b.btnLoop.setOnClickListener { v ->
            val next = when (player.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
                Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
                else -> Player.REPEAT_MODE_OFF
            }
            player.repeatMode = next
            Prefs.setPlayerRepeat(this, next)
            applyModes()
            Motion.haptic(v)
            modeFeedback(if (next == Player.REPEAT_MODE_ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat, when (next) {
                Player.REPEAT_MODE_ONE -> "Loop one"
                Player.REPEAT_MODE_ALL -> "Loop all"
                else -> "Loop off"
            })
        }
        b.btnAutoNext.setOnClickListener { v ->
            val on = !Prefs.autoplayNext(this)
            Prefs.setAutoplayNext(this, on)
            applyModes()
            Motion.haptic(v)
            modeFeedback(R.drawable.ic_autoplay_next, if (on) "Auto-play on" else "Auto-play off")
        }
        renderModes()
        renderRotateButton()
    }

    private fun modeFeedback(icon: Int, text: String) {
        renderModes()
        updateNavButtons()
        updatePipParams()
        showIndicator(icon, text, null)
        scheduleHide()
    }

    /**
     * Applies Loop / Auto-play next to the engine. Auto-play next off = pause at the end of each video
     * (unless a loop mode is on: looping one video or the queue means "keep going").
     */
    private fun applyModes() {
        if (!playerReady) return
        player.pauseAtEndOfMediaItems = !Prefs.autoplayNext(this) && player.repeatMode == Player.REPEAT_MODE_OFF
    }

    /**
     * v2.3: a fresh shuffle order that starts at the current video, so turning Shuffle on (or a shuffle-play launch)
     * plays every other queued video once. Only ExoPlayer's play order changes: the queue, the saved session order
     * and any saved playlist keep their own order. With Shuffle off this has no effect on Next/Previous.
     */
    private fun resetShuffleOrder() {
        if (!playerReady) return
        val n = player.mediaItemCount
        if (n < 2) return
        val cur = player.currentMediaItemIndex.coerceIn(0, n - 1)
        val rest = (0 until n).filter { it != cur }.shuffled()
        val order = IntArray(n).also { it[0] = cur; rest.forEachIndexed { i, v -> it[i + 1] = v } }
        try { player.setShuffleOrder(DefaultShuffleOrder(order, System.nanoTime())) } catch (_: Exception) { }
    }

    private fun renderModes() {
        if (!playerReady) return
        val accent = ThemeManager.primary(this)
        fun style(v: ImageView, on: Boolean, desc: String) {
            v.imageTintList = android.content.res.ColorStateList.valueOf(if (on) accent else 0xFFFFFFFF.toInt())
            v.alpha = if (on) 1f else 0.72f
            v.isSelected = on
            v.contentDescription = desc
            TooltipCompat.setTooltipText(v, desc)
        }
        val shuffle = player.shuffleModeEnabled
        style(b.btnShuffle, shuffle, if (shuffle) "Shuffle: on" else "Shuffle: off")
        val rep = player.repeatMode
        b.btnLoop.setImageResource(if (rep == Player.REPEAT_MODE_ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat)
        style(b.btnLoop, rep != Player.REPEAT_MODE_OFF, when (rep) {
            Player.REPEAT_MODE_ONE -> "Loop: this video"
            Player.REPEAT_MODE_ALL -> "Loop: whole queue"
            else -> "Loop: off"
        })
        val auto = Prefs.autoplayNext(this)
        style(b.btnAutoNext, auto, if (auto) "Auto-play next: on" else "Auto-play next: off")
    }

    // ------------------------------------------------------------------ v2.1: long-press temporary speed

    private fun startTempSpeed() {
        if (tempSpeedActive || !playerReady) return
        val target = Prefs.longPressSpeed(this)
        tempSpeedPrev = player.playbackParameters.speed
        if (abs(target - tempSpeedPrev) < 0.001f) return
        tempSpeedActive = true
        player.setPlaybackSpeed(target)
        // Show the speed the engine really uses, never just the requested one.
        val actual = player.playbackParameters.speed
        showIndicator(R.drawable.ic_fast_forward, formatSpeed(actual).replace("x", "×") + " speed", null, sticky = true)
        Motion.haptic(b.gestureLayer, long = true)
    }

    /** Restores the speed from before the hold. Never saved as the user's speed. */
    private fun endTempSpeed() {
        if (!tempSpeedActive) return
        tempSpeedActive = false
        if (playerReady) player.setPlaybackSpeed(tempSpeedPrev)
        if (::chip.isInitialized) chip.hideSoon(250)
    }

    // ------------------------------------------------------------------ v2.1: volume boost

    /** v2.2: boost is always on; it is only unavailable when the device/audio path can't amplify. */
    private fun boostAvailable() = !boost.unsupported

    /** Swipe level in "system steps": 0..max is system volume, max..2*max is the player's boost. */
    private fun currentVolumeLevel(): Float {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (boost.boostPct > 0 && cur < max) setBoost(0) // volume lowered elsewhere: drop the boost
        return cur + if (boost.boostPct > 0) boost.boostPct / 100f * max else 0f
    }

    private fun applyVolumeLevel(prev: Float, max: Int) {
        if (max <= 0) return
        if (volumeLevel <= max) {
            if (boost.boostPct > 0) setBoost(0)
            try { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volumeLevel.roundToInt(), 0) } catch (_: SecurityException) { }
            val pct = (volumeLevel / max * 100).roundToInt()
            showSideLevel(if (pct == 0) R.drawable.ic_volume_off else R.drawable.ic_volume, pct)
            return
        }
        // Boost range: only when crossing up from below do we top the system volume up to its maximum.
        // While inside the boost range the system volume is never changed; only the player's gain is.
        if (prev <= max && audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) < max) {
            try { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0) } catch (_: SecurityException) { }
        }
        val pct = ((volumeLevel - max) / max * 100).roundToInt().coerceIn(1, 100)
        if (setBoost(pct)) showSideLevel(R.drawable.ic_volume, 100 + pct, boosted = true)
        else { volumeLevel = max.toFloat(); showSideLevel(R.drawable.ic_volume, 100) }
    }

    private fun ensureAudioSession(): Int {
        var id = player.audioSessionId
        if (id == C.AUDIO_SESSION_ID_UNSET) {
            id = audioManager.generateAudioSessionId()
            if (id > 0) player.setAudioSessionId(id) else id = 0
        }
        return id
    }

    /** 0..100 extra percent. Returns false (and explains once) when the device can't amplify this audio. */
    private fun setBoost(pct: Int): Boolean {
        if (!playerReady) return false
        val ok = boost.set(if (pct > 0) ensureAudioSession() else player.audioSessionId, pct)
        if (!ok && pct > 0 && !boostWarned) {
            boostWarned = true
            notice(R.drawable.ic_volume, "Volume boost isn't supported for this audio")
        }
        return ok
    }

    /** Hardware volume keys continue into / out of the boost range when boost is enabled. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (playerReady && boostAvailable() && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val up = keyCode == KeyEvent.KEYCODE_VOLUME_UP
            if ((up && cur >= max) || (!up && boost.boostPct > 0 && cur >= max)) {
                val p = (boost.boostPct + if (up) 10 else -10).coerceIn(0, 100)
                if (setBoost(p)) {
                    dragSide = Prefs.volumeSide(this)
                    if (p > 0) showSideLevel(R.drawable.ic_volume, 100 + p, boosted = true) else showSideLevel(R.drawable.ic_volume, 100)
                    chip.hideSoon(900)
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ------------------------------------------------------------------ v2.1: three-finger frame capture

    /** Saves the current video frame (no controls or subtitles) to Pictures/VelaPlayer. */
    private fun captureFrame() {
        if (!playerReady) return
        if (vaultMode) { notice(R.drawable.ic_info, "Frame capture is off for private videos"); return }
        if (Build.VERSION.SDK_INT < 29 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestWrite.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE); return
        }
        if (player.videoFormat?.drmInitData != null) {
            notice(R.drawable.ic_info, "This video is DRM-protected, frames can't be captured"); return
        }
        val vs = player.videoSize
        if (vs.width <= 0 || vs.height <= 0) { notice(R.drawable.ic_info, "No video frame to capture yet"); return }
        var w = (vs.width * (if (vs.pixelWidthHeightRatio > 0f) vs.pixelWidthHeightRatio else 1f)).roundToInt()
        var h = vs.height
        val scale = min(1f, 3840f / maxOf(w, h))
        w = (w * scale).roundToInt().coerceAtLeast(1); h = (h * scale).roundToInt().coerceAtLeast(1)
        val bmp = try { if (b.videoLayout.textureView.isAvailable) b.videoLayout.textureView.getBitmap(w, h) else null } catch (_: Throwable) { null }
        if (bmp == null) { notice(R.drawable.ic_info, "Couldn't capture this frame"); return }
        Motion.haptic(b.gestureLayer, long = true)
        b.videoLayout.animate().cancel()
        b.videoLayout.animate().alpha(0.35f).setDuration(70).withEndAction { b.videoLayout.animate().alpha(1f).setDuration(240).start() }.start()
        val name = FrameCapture.fileName(player.currentMediaItem?.mediaMetadata?.title?.toString(), player.currentPosition)
        val app = applicationContext
        Thread {
            val result = runCatching { FrameCapture.save(app, bmp, name) }
            bmp.recycle()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (result.isSuccess) notice(R.drawable.ic_check_circle, "Screenshot saved to Pictures/${FrameCapture.FOLDER}")
                else notice(R.drawable.ic_info, "Couldn't save the screenshot: ${result.exceptionOrNull()?.message ?: "storage error"}")
            }
        }.start()
    }

    // ------------------------------------------------------------------ v2.2: vault playback mode

    /** Switches vault behaviour on/off for the queue being loaded (private titles, no app-switcher preview, no library browser). */
    private fun setVaultMode(on: Boolean) {
        vaultMode = on
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (::focus.isInitialized) focus.privateMode = on
        if (on && browserOpen) closeBrowser(false)
        if (!on) realTitles.clear()
        if (::b.isInitialized) renderFocusUi()
    }

    // ------------------------------------------------------------------ v2.3: playback message chip position

    /**
     * Where the shared message chip sits (it is bottom|center_horizontal in the root, so translationY is negative).
     * - Controls showing: below the timeline, centred on the bottom button row in its free middle gap.
     *   If that gap is too narrow for this message (small portrait screens), it sits on the mode-row line just above
     *   the timeline instead, clear of the Shuffle/Loop/Auto-play buttons.
     * - Controls hidden / locked: near the bottom edge, slightly smaller, above the navigation bar / cutout.
     */
    private fun chipPlacement(w: Int, h: Int): PlaybackChip.Placement {
        val rootW = b.root.width.toFloat()
        val rootH = b.root.height.toFloat()
        val hidden = PlaybackChip.Placement(0f, -(safeBottom + dp(10)).toFloat(), 0.92f)
        if (rootW <= 0f || rootH <= 0f || !controlsVisible || !b.controls.isVisible || locked) return hidden
        val bar = b.bottomBar
        val row = b.p23ButtonRow
        val gap = b.p23RowGap
        if (row.height == 0 || bar.height == 0) return hidden
        // Layout positions only (ignores the slide-in translation of the bar), so the chip lands where the bar settles.
        val barTop = b.controls.top + bar.top.toFloat()
        val barLeft = b.controls.left + bar.left.toFloat()
        val rowCy = barTop + row.top + row.height / 2f
        val gapL = barLeft + row.left + gap.left + dp(4)
        val gapR = barLeft + row.left + gap.right - dp(4)
        if (gapR - gapL >= w) {
            val cx = (rootW / 2f).coerceIn(gapL + w / 2f, gapR - w / 2f)
            return PlaybackChip.Placement(cx - rootW / 2f, -(rootH - rowCy - h / 2f), 1f)
        }
        val mode = b.modeRow
        val modeCy = barTop + mode.top + mode.height / 2f
        val limL = barLeft + bar.paddingLeft + dp(8)
        val limR = barLeft + mode.left - dp(8)
        if (limR - limL >= w) {
            val cx = (rootW / 2f).coerceIn(limL + w / 2f, limR - w / 2f)
            return PlaybackChip.Placement(cx - rootW / 2f, -(rootH - modeCy - h / 2f), 1f)
        }
        return PlaybackChip.Placement(0f, -(rootH - (barTop + mode.top) + dp(4)), 1f)
    }

    // ------------------------------------------------------------------ v2.2: PiP rewind / forward feedback

    /**
     * Android doesn't pass touches to an app's PiP window (the system uses taps for its own overlay), so double-tap
     * can't be detected there. The PiP rewind/forward buttons are the reliable alternative; this shows what they did.
     */
    private fun pipSeek(side: Int) {
        val seekable = player.isCurrentMediaItemSeekable && !player.isCurrentMediaItemLive && player.duration != C.TIME_UNSET
        if (!seekable) return
        val moved = seekBy(side * seekIntervalMs)
        if (!isInPipSafe()) { showSeekFeedback(side, moved); return }
        updatePipProgress()
        val now = SystemClock.uptimeMillis()
        if (side != pipSeekSide || now - pipSeekTime > 1200) pipSeekAccum = 0L
        pipSeekSide = side; pipSeekTime = now
        pipSeekAccum += abs(moved)
        val secs = (pipSeekAccum + 500) / 1000
        b.p22PipSeek.text = when {
            secs == 0L -> if (side < 0) "⟲ start" else "end ⟳"
            side < 0 -> "⟲ ${secs}s"
            else -> "${secs}s ⟳"
        }
        b.p22PipSeek.contentDescription = if (side < 0) "Rewound $secs seconds" else "Forwarded $secs seconds"
        handler.removeCallbacks(hidePipSeekRunnable)
        fadeIn(b.p22PipSeek)
        handler.postDelayed(hidePipSeekRunnable, 1100)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
