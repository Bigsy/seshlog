package com.hedworth.seshlog.review

import com.intellij.openapi.components.Service
import com.intellij.openapi.application.ApplicationManager
import java.util.concurrent.CopyOnWriteArrayList

/** No persistent state: comments, snapshots and sent batches end with this IDE process. */
@Service(Service.Level.APP)
internal class ReviewDraftService {
    val store = ReviewDraftStore()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    fun changed() = listeners.forEach { it() }
    fun listen(listener: () -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }
    companion object {
        fun getInstance(): ReviewDraftService = ApplicationManager.getApplication().getService(ReviewDraftService::class.java)
    }
}
