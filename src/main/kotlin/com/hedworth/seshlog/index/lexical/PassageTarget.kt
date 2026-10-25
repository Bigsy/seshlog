package com.hedworth.seshlog.index.lexical

import com.hedworth.seshlog.index.SearchHit
import com.hedworth.seshlog.model.ConversationEntry

/** A changed source never receives an old range. Compute the hash on a worker before rendering. */
fun validatedPassage(hit: SearchHit?, entries: List<ConversationEntry>): Pair<Int,IntRange>? {
    val id=hit?.entryId ?: return null
    val range=hit.range ?: return null
    val index=entries.indexOfFirst { it.sourceId==id && it.searchable }
    if(index<0 || range.isEmpty() || range.first<0 || range.last>=entries[index].text.length)return null
    return if(hit.entryHash!=null && hit.entryHash==searchHash(entries[index].text))index to range else null
}
