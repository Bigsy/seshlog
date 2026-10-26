package com.hedworth.seshlog.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Paths

class SeshlogSettingsTest {
    private val home = Paths.get(System.getProperty("user.home"))

    @Test fun `old settings use conversation and all eight source selections serialize including all false`() {
        val old = com.intellij.util.xmlb.XmlSerializer.deserialize(org.jdom.Element("state"), SeshlogSettings.State::class.java)
        val settings = SeshlogSettings()
        settings.loadState(old)
        assertEquals(com.hedworth.seshlog.index.SearchSources.DEFAULT, settings.searchSources)
        for (bits in 0..7) {
            val sources = com.hedworth.seshlog.index.SearchSources(bits and 1 != 0, bits and 2 != 0, bits and 4 != 0)
            settings.searchSources = sources
            val xml = com.intellij.util.xmlb.XmlSerializer.serialize(settings.state)
            val restored = SeshlogSettings()
            restored.loadState(com.intellij.util.xmlb.XmlSerializer.deserialize(xml, SeshlogSettings.State::class.java))
            assertEquals(sources, restored.searchSources)
            restored.previewMessageCount = 4
            assertEquals(sources, restored.searchSources)
        }
    }

    @Test
    fun `opencode data dir follows XDG_DATA_HOME, else the XDG default`() {
        assertEquals(Paths.get("/x/data/opencode"), SeshlogSettings.defaultOpenCodeDataDir(mapOf("XDG_DATA_HOME" to "/x/data")))
        assertEquals(home.resolve("xdg/opencode"), SeshlogSettings.defaultOpenCodeDataDir(mapOf("XDG_DATA_HOME" to "~/xdg")))
        assertEquals(home.resolve(".local/share/opencode"), SeshlogSettings.defaultOpenCodeDataDir(emptyMap()))
        assertEquals(home.resolve(".local/share/opencode"), SeshlogSettings.defaultOpenCodeDataDir(mapOf("XDG_DATA_HOME" to " ")))
    }

    @Test
    fun `a configured opencode data dir wins over the environment`() {
        val env = mapOf("XDG_DATA_HOME" to "/x/data")
        assertEquals(Paths.get("/x/data/opencode"), SeshlogSettings.resolveOpenCodeDataDir("", env))
        assertEquals(home.resolve("oc"), SeshlogSettings.resolveOpenCodeDataDir(" ~/oc ", env))
        assertEquals(Paths.get("/srv/opencode"), SeshlogSettings.resolveOpenCodeDataDir("/srv/opencode", env))
    }
}
