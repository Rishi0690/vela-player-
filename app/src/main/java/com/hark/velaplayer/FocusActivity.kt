package com.hark.velaplayer

import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.hark.velaplayer.databinding.ActivityFocusBinding
import com.hark.velaplayer.databinding.ItemStatBinding
import java.util.concurrent.Executors

/** Focus dashboard: today, week, all time, a 14-day chart, recent sessions and per-video focus time. */
class FocusActivity : ThemedActivity() {
    private lateinit var b: ActivityFocusBinding
    private val io = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityFocusBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.toolbar.setNavigationOnClickListener { finish() }
        b.btnSettings.setOnClickListener { openFocusSettings() }
        b.goalButton.setOnClickListener { openFocusSettings() }
        b.btnClear.setOnClickListener {
            MaterialAlertDialogBuilder(this).setTitle("Clear focus history?")
                .setMessage("All focus sessions, daily totals and per-video focus times will be deleted from this phone. Your videos, watch progress and settings are not affected.")
                .setPositiveButton("Clear") { _, _ -> io.execute { FocusStore.get(this).clearAll(); runOnUiThread { load() } } }
                .setNegativeButton("Cancel", null).show()
        }
    }

    override fun onResume() { super.onResume(); load() }
    override fun onDestroy() { io.shutdown(); super.onDestroy() }

    private fun openFocusSettings() = startActivity(Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_SECTION, "focus"))

    private class Data(
        val today: Long, val week: Long, val all: Long, val videos: Long, val done: Long, val sessions: Long,
        val days: List<Pair<Long, Long>>, val recent: List<FocusStore.Session>, val top: List<FocusStore.VideoStat>
    )

    private fun load() {
        io.execute {
            val s = FocusStore.get(this)
            val d = Data(s.today(), s.thisWeek(), s.allTime(), s.videosWatched(), s.videosCompleted(), s.sessionCount(), s.lastDays(14), s.recentSessions(15), s.topVideos(12))
            runOnUiThread { if (!isFinishing && !isDestroyed) render(d) }
        }
    }

    private fun stat(v: ItemStatBinding, value: String, label: String) { v.statValue.text = value; v.statLabel.text = label }

    private fun render(d: Data) {
        val goal = Prefs.focusGoalMs(this)
        b.todayValue.text = FocusFmt.dur(d.today)
        b.goalBar.isVisible = goal > 0
        b.goalButton.isVisible = goal <= 0
        if (goal > 0) {
            b.goalBar.setProgressCompat(((d.today.toFloat() / goal) * 1000).toInt().coerceIn(0, 1000), true)
            b.goalText.text = if (d.today >= goal) "Goal of ${FocusFmt.dur(goal)} reached today. Nice work!"
            else "${FocusFmt.dur(goal - d.today)} left to reach your ${FocusFmt.dur(goal)} goal"
        } else b.goalText.text = "Set a daily goal to see your progress here."
        stat(b.statWeek, FocusFmt.dur(d.week), "This week")
        stat(b.statAll, FocusFmt.dur(d.all), "All time")
        stat(b.statVideos, d.videos.toString(), "Videos watched")
        stat(b.statDone, d.done.toString(), "Finished")
        stat(b.statSessions, d.sessions.toString(), "Sessions")
        b.chart.setData(d.days, goal)

        b.sessions.removeAllViews()
        if (d.recent.isEmpty()) b.sessions.addView(hint("No focus sessions yet. Start Focus Mode from the player's top bar or its options menu."))
        d.recent.forEach { se ->
            val date = DateUtils.formatDateTime(this, se.start, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY)
            val time = DateFormat.getTimeFormat(this).format(se.start)
            val extra = listOfNotNull(
                if (se.videos > 0) "${se.videos} video${if (se.videos == 1) "" else "s"}" else null,
                if (se.completed > 0) "${se.completed} finished" else null
            ).joinToString(" · ")
            b.sessions.addView(row(FocusFmt.dur(se.focusMs) + " focused", "$date, $time" + if (extra.isNotEmpty()) " · $extra" else ""))
        }

        b.videos.removeAllViews()
        if (d.top.isEmpty()) b.videos.addView(hint("Videos you watch in Focus Mode will show here with the real time you spent on each."))
        d.top.forEach { v ->
            val parts = ArrayList<String>()
            parts += "Focus ${FocusFmt.dur(v.focusMs)}"
            if (v.mediaMs > 0) parts += "Video length ${Fmt.time(v.mediaMs)}"
            val pos = Store.position(this, v.uri); val dur = Store.duration(this, v.uri)
            parts += when {
                v.completed || Store.watchedSet(this).contains(v.uri) -> "Finished"
                pos > 0 && dur > 0 -> "Watched ${(pos * 100 / dur)}%"
                else -> "Not finished"
            }
            b.videos.addView(row(v.title.ifBlank { "Video" }, parts.joinToString(" · ")))
        }
    }

    private fun hint(text: String) = TextView(this).apply {
        this.text = text; alpha = 0.7f
        androidx.core.widget.TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
    }

    private fun row(title: String, sub: String): LinearLayout {
        val dp = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((14 * dp).toInt(), (10 * dp).toInt(), (14 * dp).toInt(), (10 * dp).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 16 * dp
                setColor(ThemeManager.attr(this@FocusActivity, com.google.android.material.R.attr.colorSurfaceVariant) and 0x00FFFFFF or 0x66000000)
            }
            addView(TextView(context).apply {
                text = title; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                androidx.core.widget.TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
            })
            addView(TextView(context).apply {
                text = sub; alpha = 0.7f
                androidx.core.widget.TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            })
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (6 * dp).toInt() }
        }
    }
}
