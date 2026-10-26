package com.hedworth.seshlog.settings

import com.hedworth.seshlog.index.SearchSources
import com.intellij.openapi.application.ApplicationManager
import com.intellij.util.messages.Topic

/** Query preference changes intentionally avoid provider/settings refresh events. */
interface SearchSourcesListener {
    fun sourcesChanged(sources: SearchSources)

    companion object {
        val TOPIC = Topic.create("Seshlog search sources", SearchSourcesListener::class.java)

        fun fire(sources: SearchSources) {
            val app = ApplicationManager.getApplication() ?: return
            val publish = Runnable {
                if (!app.isDisposed) app.messageBus.syncPublisher(TOPIC).sourcesChanged(sources)
            }
            if (app.isDispatchThread) publish.run() else app.invokeLater(publish)
        }
    }
}
