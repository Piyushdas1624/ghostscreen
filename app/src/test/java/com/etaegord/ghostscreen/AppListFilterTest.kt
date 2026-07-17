package com.etaegord.ghostscreen

import com.etaegord.ghostscreen.util.AppListFilter
import com.etaegord.ghostscreen.util.AppListFilter.Scope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Smart Mask filter pipeline (scope -> search) must be a pure, deterministic
 * function of (allApps, scope, query, protectedSet). This is the regression
 * guard for the filter-chip crash: the visible list must be derivable in one
 * pass with no mid-composition mutation.
 */
class AppListFilterTest {

    private data class Row(val pkg: String, val name: String)

    private val all = listOf(
        Row("com.whatsapp", "WhatsApp"),
        Row("com.bank", "BankApp"),
        Row("com.calculator", "Calculator"),
        Row("com.ghostscreen", "GhostScreen")
    )
    private val protected = setOf("com.whatsapp", "com.bank")

    private fun filter(scope: Scope, query: String) = AppListFilter.apply(
        all, scope, query, protected,
        packageName = { it.pkg }, appName = { it.name }
    )

    @Test
    fun all_scope_returns_everything_when_query_blank() {
        assertEquals(all, filter(Scope.ALL, ""))
    }

    @Test
    fun enabled_scope_keeps_only_protected() {
        val r = filter(Scope.ENABLED, "")
        assertEquals(2, r.size)
        assertTrue(r.all { it.pkg in protected })
    }

    @Test
    fun disabled_scope_keeps_only_unprotected() {
        val r = filter(Scope.DISABLED, "")
        assertEquals(2, r.size)
        assertTrue(r.none { it.pkg in protected })
    }

    @Test
    fun search_matches_name_case_insensitive() {
        val r = filter(Scope.ALL, "whats")
        assertEquals(listOf("com.whatsapp"), r.map { it.pkg })
    }

    @Test
    fun search_matches_package() {
        val r = filter(Scope.ALL, "com.bank")
        assertEquals(listOf("com.bank"), r.map { it.pkg })
    }

    @Test
    fun search_intersects_with_scope() {
        // WhatsApp is protected; searching it under DISABLED must return nothing.
        assertEquals(emptyList<Row>(), filter(Scope.DISABLED, "whats"))
        // Under ENABLED it returns WhatsApp only.
        assertEquals(listOf("com.whatsapp"), filter(Scope.ENABLED, "whats").map { it.pkg })
    }

    @Test
    fun whitespace_only_query_is_treated_as_no_filter() {
        // Matches the original screen behavior (isBlank => no filter): a
        // whitespace-only search must not collapse the list to empty.
        assertEquals(all, filter(Scope.ALL, "   "))
    }

    @Test
    fun blank_query_returns_scoped_list_unchanged() {
        // Crucial for the crash fix: a blank query never re-filters to empty.
        assertEquals(2, filter(Scope.ENABLED, "").size)
    }

    @Test
    fun self_entry_decodes_through_filter_without_special_casing() {
        // The app's own entry must flow through the filter like any other row.
        val r = filter(Scope.ALL, "GhostScreen")
        assertEquals(listOf("com.ghostscreen"), r.map { it.pkg })
    }
}
