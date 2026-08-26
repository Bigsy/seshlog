package com.hedworth.seshlog.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.util.messages.Topic

/** Broadcast after Settings | Tools | Seshlog is applied, so open tool windows can re-read settings. */
interface SeshlogSettingsListener {
    fun settingsChanged()

    companion object {
        @JvmField
        val TOPIC: Topic<SeshlogSettingsListener> =
            Topic.create("Seshlog settings", SeshlogSettingsListener::class.java)

        fun fire() = ApplicationManager.getApplication().messageBus.syncPublisher(TOPIC).settingsChanged()
    }
}
