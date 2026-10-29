package com.hedworth.seshlog.index

import com.hedworth.seshlog.model.ConversationEntry
import com.hedworth.seshlog.model.EntryKind
import com.hedworth.seshlog.model.Role

/** Query evidence only: collection, retention and the displayed conversation are unchanged. */
data class SearchSources(
    val userMessages: Boolean,
    val assistantReplies: Boolean,
    val toolActivity: Boolean,
) {
    fun includes(source: SearchEntrySource): Boolean = when (source) {
        SearchEntrySource.USER -> userMessages
        SearchEntrySource.ASSISTANT -> assistantReplies
        SearchEntrySource.TOOL_CALL, SearchEntrySource.TOOL_RESULT -> toolActivity
    }

    val summary: String get() = when (this) {
        DEFAULT -> "Conversation"
        ALL -> "All activity"
        METADATA_ONLY -> "Titles & paths"
        SearchSources(true, false, false) -> "Your messages"
        SearchSources(false, true, false) -> "Assistant replies"
        SearchSources(false, false, true) -> "Tool activity"
        SearchSources(true, false, true) -> "You + tools"
        else -> "Assistant + tools"
    }

    val description: String get() {
        val included = listOfNotNull(
            "Your messages".takeIf { userMessages },
            "Assistant replies".takeIf { assistantReplies },
            "Tool activity".takeIf { toolActivity },
        )
        return (if (included.isEmpty()) "No conversation sources selected." else included.joinToString(", ") + ".") +
            " Session titles and project paths are always included."
    }

    companion object {
        val DEFAULT = SearchSources(true, true, false)
        val ALL = SearchSources(true, true, true)
        val METADATA_ONLY = SearchSources(false, false, false)
    }
}

enum class SearchEntrySource { USER, ASSISTANT, TOOL_CALL, TOOL_RESULT;
    val matchSource: SearchMatchSource get() = SearchMatchSource.valueOf(name)
}

/** Declaration order is the deterministic primary-evidence tie order. */
enum class SearchMatchSource(val label: String) {
    TITLE("Title"), PROJECT_PATH("Project path"), USER("You"), ASSISTANT("Assistant"),
    TOOL_CALL("Tool call"), TOOL_RESULT("Tool result");

    val entrySource: SearchEntrySource? get() = when (this) {
        TITLE, PROJECT_PATH -> null
        else -> SearchEntrySource.valueOf(name)
    }
}

enum class SearchMetadataVariant { PROVIDER_TITLE, LOCAL_TITLE, PROJECT_PATH }

data class SearchContext(
    val query: String,
    val sources: SearchSources = SearchSources.DEFAULT,
    val sort: SearchSort = SearchSort.BEST_MATCH,
)

/** Metadata has no fabricated body target. Body identity/range/hash refer to one original entry. */
data class SearchPrimaryMatch(
    val source: SearchMatchSource,
    val entryId: String? = null,
    val range: IntRange? = null,
    val entryHash: String? = null,
    val metadataVariant: SearchMetadataVariant? = null,
    val toolName: String? = null,
)

val ConversationEntry.searchSource: SearchEntrySource? get() = when (kind) {
    EntryKind.COVERAGE -> null
    EntryKind.TOOL_CALL -> SearchEntrySource.TOOL_CALL
    EntryKind.TOOL_RESULT -> SearchEntrySource.TOOL_RESULT
    EntryKind.DIALOGUE -> if (message.role == Role.USER) SearchEntrySource.USER else SearchEntrySource.ASSISTANT
}

fun ConversationEntry.eligibleFor(sources: SearchSources): Boolean = searchSource?.let(sources::includes) == true
