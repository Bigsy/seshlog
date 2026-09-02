package com.hedworth.seshlog.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Paths

class SeshlogSettingsTest {
    private val home = Paths.get(System.getProperty("user.home"))

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
