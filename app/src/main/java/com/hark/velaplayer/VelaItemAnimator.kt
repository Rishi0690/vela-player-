package com.hark.velaplayer

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.view.View
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.RecyclerView

/**
 * v2.12 list motion. DefaultItemAnimator (moves/removals unchanged, no change cross-fades) with a staggered
 * "rise" for added rows: each new row fades in (DefaultItemAnimator's alpha) while it lifts 10 dp and grows from
 * 0.96, one after another. Used for search results, filters, playlist edits and selection changes; positions never
 * jump because DiffUtil drives the moves.
 */
class VelaItemAnimator : DefaultItemAnimator() {
    private var batchIndex = 0
    private val lifts = HashMap<RecyclerView.ViewHolder, ObjectAnimator>()
    var enabledMotion = true

    init {
        supportsChangeAnimations = false
        addDuration = 300; removeDuration = 180; moveDuration = 300
    }

    override fun animateAdd(holder: RecyclerView.ViewHolder): Boolean {
        // super first: it resets any running animation on this holder (which would cancel ours).
        val r = super.animateAdd(holder)
        val v = holder.itemView
        if (enabledMotion && Motion.enabled(v.context)) {
            val delay = (batchIndex.coerceAtMost(12)) * Motion.STAGGER
            batchIndex++
            v.animate().startDelay = delay
            val dy = 10 * v.resources.displayMetrics.density
            v.translationY = dy; v.scaleX = 0.96f; v.scaleY = 0.96f
            lifts.remove(holder)?.cancel()
            lifts[holder] = ObjectAnimator.ofPropertyValuesHolder(v,
                PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, dy, 0f),
                PropertyValuesHolder.ofFloat(View.SCALE_X, 0.96f, 1f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.96f, 1f)).apply {
                duration = addDuration + 80; startDelay = delay; interpolator = Motion.emphasized
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) { v.translationY = 0f; v.scaleX = 1f; v.scaleY = 1f }
                })
            }
        }
        return r
    }

    override fun onAddStarting(item: RecyclerView.ViewHolder) {
        super.onAddStarting(item)
        lifts[item]?.start()
    }

    override fun onAddFinished(item: RecyclerView.ViewHolder) {
        super.onAddFinished(item)
        item.itemView.animate().startDelay = 0
        lifts.remove(item)?.let { if (it.isRunning || it.isStarted) it.end() else { item.itemView.translationY = 0f; item.itemView.scaleX = 1f; item.itemView.scaleY = 1f } }
    }

    override fun runPendingAnimations() {
        super.runPendingAnimations()
        batchIndex = 0
    }

    override fun endAnimation(item: RecyclerView.ViewHolder) {
        lifts.remove(item)?.let { it.cancel(); item.itemView.translationY = 0f; item.itemView.scaleX = 1f; item.itemView.scaleY = 1f }
        item.itemView.animate().startDelay = 0
        super.endAnimation(item)
    }

    override fun endAnimations() {
        lifts.forEach { (h, a) -> a.cancel(); h.itemView.translationY = 0f; h.itemView.scaleX = 1f; h.itemView.scaleY = 1f; h.itemView.animate().startDelay = 0 }
        lifts.clear()
        super.endAnimations()
    }
}
