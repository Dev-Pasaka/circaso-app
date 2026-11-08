package com.circaso.core.navigation

/**
 * Each Home tab's own screens in the detail pane (Pasaka, 2026-11-08: sub-screens opened from You stayed, and came
 * back on Back, after moving to Chats). The rule, as on iOS tab bars: what a tab opened belongs to that tab. Moving to
 * another tab takes the old tab's screens off the back stack and puts back the ones the new tab had open, so Back
 * never shows another tab's screens. On one pane (a phone) a tab can only be changed from Home itself, and nothing is
 * kept: a tab tap must never push screens over Home. When Home itself leaves the stack (signing out), every tab's
 * screens go with it ([clear]).
 */
class TabStacks<K : Any> {
    private val stashed = mutableMapOf<Int, List<K>>()

    /** The screens [tab] will get back when it is chosen again (tests). */
    fun keptFor(tab: Int): List<K> = stashed[tab].orEmpty()

    /**
     * Moves [stack] from tab [from] to tab [to]: what is above [home] is set aside for [from] (when [keep], two panes)
     * and what [to] had is put back; without [keep] both are dropped. A stack without [home] has nothing to move.
     */
    fun switch(stack: MutableList<K>, home: K, from: Int, to: Int, keep: Boolean) {
        if (from == to) return
        val at = stack.lastIndexOf(home)
        if (at < 0) { stashed.clear(); return }
        val above = stack.subList(at + 1, stack.size).toList()
        repeat(above.size) { stack.removeAt(stack.lastIndex) }
        if (!keep) { stashed.clear(); return }
        if (above.isEmpty()) stashed.remove(from) else stashed[from] = above
        stashed.remove(to)?.let { stack.addAll(it) }
    }

    /** Forgets [tab]'s screens (a notification opens something fresh on it). */
    fun forget(tab: Int) { stashed.remove(tab) }

    /** Home left the stack: no tab's screens may come back. */
    fun clear() = stashed.clear()
}
