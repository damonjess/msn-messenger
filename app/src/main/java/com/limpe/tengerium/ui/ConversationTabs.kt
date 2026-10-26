package com.limpe.tengerium.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Open conversation tabs, shared by every chat window. A single chat fragment is shown at a
 * time (the detail pane), and this list supplies the tab strip above it - the same idea as
 * stacking several conversation windows into one in MSN Messenger 7.
 */
object ConversationTabs {

    data class Tab(val account: String, val title: String)

    private const val MAX_TABS = 8

    private val _tabs = MutableStateFlow<List<Tab>>(emptyList())
    val tabs: StateFlow<List<Tab>> = _tabs.asStateFlow()

    fun normalize(account: String): String = account.lowercase().trim()

    /** Opens (or focuses) a conversation. Returns the resulting list. */
    @Synchronized
    fun open(account: String, title: String): List<Tab> {
        val key = normalize(account)
        if (key.isEmpty()) return _tabs.value
        val existing = _tabs.value
        if (existing.any { it.account == key }) return existing
        val cleanTitle = title.trim().ifBlank { key.substringBefore('@') }
        val updated = (existing + Tab(key, cleanTitle)).takeLast(MAX_TABS)
        _tabs.value = updated
        return updated
    }

    /** Updates the label of an open tab (e.g. when a nickname arrives). */
    @Synchronized
    fun updateTitle(account: String, title: String) {
        val key = normalize(account)
        val cleanTitle = title.trim()
        if (key.isEmpty() || cleanTitle.isEmpty()) return
        val updated = _tabs.value.map {
            if (it.account == key && it.title != cleanTitle) it.copy(title = cleanTitle) else it
        }
        if (updated != _tabs.value) _tabs.value = updated
    }

    /** Closes a tab and returns what is left. */
    @Synchronized
    fun close(account: String): List<Tab> {
        val key = normalize(account)
        val updated = _tabs.value.filterNot { it.account == key }
        if (updated.size != _tabs.value.size) _tabs.value = updated
        return updated
    }

    /** Drops every tab (used when signing out). */
    @Synchronized
    fun clear() {
        _tabs.value = emptyList()
    }
}
