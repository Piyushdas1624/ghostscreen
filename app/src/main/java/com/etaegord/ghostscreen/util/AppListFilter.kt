package com.etaegord.ghostscreen.util

/**
 * Pure, Android-free filtering of the Smart Mask app list.
 *
 * Extracted from SmartMaskSettingsScreen so the exact filter pipeline
 * (filterMode -> search -> partition) is unit-testable on the JVM and so the
 * Compose layer holds a single deterministic derivation of what to render.
 *
 * The filter-chip crash root cause was that the list mutation (a DB toggle)
 * re-emitted the Room Flow and re-derived the visible list *mid-composition*
 * while a row's onToggle coroutine was still in flight — under the "Enabled"
 * filter the toggled row vanished instantly and the row's key dropped out from
 * under the running toggle. Centralizing the filter here lets the screen derive
 * it once via derivedStateOf and treat the result as a stable snapshot.
 */
object AppListFilter {

    /** Filter scope selectable from the UI chips. */
    enum class Scope { ALL, ENABLED, DISABLED }

    /**
     * Apply [scope] then the [query] substring match (case-insensitive, matches
     * either app name or package name). A blank query is a no-op. Order is
     * preserved from the input.
     */
    fun <T> apply(
        all: List<T>,
        scope: Scope,
        query: String,
        protectedSet: Set<String>,
        packageName: (T) -> String,
        appName: (T) -> String
    ): List<T> {
        val byScope = when (scope) {
            Scope.ALL -> all
            Scope.ENABLED -> all.filter { protectedSet.contains(packageName(it)) }
            Scope.DISABLED -> all.filter { !protectedSet.contains(packageName(it)) }
        }
        val q = query.trim()
        if (q.isEmpty()) return byScope
        return byScope.filter {
            appName(it).contains(q, ignoreCase = true) ||
                packageName(it).contains(q, ignoreCase = true)
        }
    }
}
