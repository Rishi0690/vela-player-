package com.hark.velaplayer

import android.view.Menu

/**
 * v2.21 multi-select action bar (Home and Vault), shared rules so both behave the same:
 * - the "fixed" items (Select all, the screen's primary action, and ⋮) are visible from the moment selection starts,
 *   even with 0 selected, and never change position (they're `showAsAction="always"`; ⋮ always has at least
 *   Invert / Clear selection in it, so the overflow button never appears or disappears);
 * - with nothing selected, ⋮ shows a disabled hint ("Select videos to see more actions") plus the actions that make
 *   sense without a selection; selection actions are hidden;
 * - the primary action's icon is dimmed (still tappable: it explains what to do) while it has nothing to act on.
 */
object SelectionMenu {
    data class State(val selected: Int, val total: Int, val noun: String = "videos")

    /** Visible/enabled state of the always-there items. [primaryReady] = the primary action has something to act on. */
    fun applyFixed(
        menu: Menu, s: State,
        allId: Int, primaryId: Int, hintId: Int, invertId: Int, clearId: Int,
        primaryReady: Boolean,
    ) {
        val any = s.selected > 0
        val allSelected = any && s.selected >= s.total
        menu.findItem(allId)?.apply { isVisible = true; isEnabled = s.total > 0; title = if (allSelected) "Deselect all" else "Select all" }
        menu.findItem(primaryId)?.apply {
            isVisible = true; isEnabled = true
            icon?.mutate()?.alpha = if (primaryReady) 255 else 110
        }
        menu.findItem(hintId)?.apply { isVisible = !any; isEnabled = false; title = "Select ${s.noun} to see more actions" }
        menu.findItem(invertId)?.apply { isVisible = s.total > 0 && any && !allSelected; isEnabled = true }
        menu.findItem(clearId)?.apply { isVisible = true; isEnabled = true; title = if (any) "Clear selection" else "Exit selection" }
    }

    /** Title shown on the bar. */
    fun title(selected: Int): String = if (selected == 0) "Select items" else "$selected selected"

    /** Keys after "Invert selection": everything selectable that wasn't selected, keeping list order. */
    fun <T> invert(all: List<T>, selected: Collection<T>): List<T> = all.filter { it !in selected }
}
