package fr.kinwaypoint.app

import fr.kinwaypoint.app.data.AppMode
import fr.kinwaypoint.app.net.RemoteConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RemoteConfigTest {

    private fun rejected(json: String, expectedMessagePart: String) {
        try {
            RemoteConfig.parse(json)
            fail("aurait dû être refusé : $json")
        } catch (e: IllegalStateException) {
            assertTrue("message « ${e.message} »", e.message!!.contains(expectedMessagePart, ignoreCase = true))
        }
    }

    @Test
    fun childConfig() {
        val cfg = RemoteConfig.parse(
            """
            {"version":1,"mode":"child","label":"Téléphone de Léa",
             "child":{"serverUrl":"https://track.exemple.fr/","deviceId":"kw7h3kq9x2mzp4","intervalSeconds":5}}
            """,
        )
        assertEquals(AppMode.CHILD, cfg.mode)
        assertEquals("https://track.exemple.fr", cfg.child!!.serverUrl)
        assertEquals("kw7h3kq9x2mzp4", cfg.child!!.deviceId)
        assertEquals(15, cfg.child!!.intervalSeconds) // ramené au minimum
        assertEquals("Téléphone de Léa", cfg.label)
        assertNull(cfg.parent)
    }

    @Test
    fun parentConfigWithoutMode() {
        val cfg = RemoteConfig.parse(
            """{"parent":{"apiUrl":"https://api.exemple.fr","trackUrl":"https://track.exemple.fr","email":"p@exemple.fr"}}""",
        )
        assertNull(cfg.mode)
        assertEquals("https://api.exemple.fr", cfg.parent!!.apiUrl)
        assertEquals("p@exemple.fr", cfg.parent!!.email)
    }

    @Test
    fun refusals() {
        rejected("pas du json", "illisible")
        rejected("""{"version":2,"child":{}}""", "trop récente")
        rejected("""{"expiresAt":"2020-01-01T00:00:00Z","child":{"serverUrl":"https://t.fr","deviceId":"kw7h3kq9x2mzp4"}}""", "expiré")
        rejected("""{"child":{"serverUrl":"http://t.fr","deviceId":"kw7h3kq9x2mzp4"}}""", "https")
        rejected("""{"child":{"serverUrl":"https://t.fr","deviceId":"court"}}""", "trop court")
        rejected("""{"mode":"parent","child":{"serverUrl":"https://t.fr","deviceId":"kw7h3kq9x2mzp4"}}""", "parent")
        rejected("""{"mode":"robot","child":{"serverUrl":"https://t.fr","deviceId":"kw7h3kq9x2mzp4"}}""", "inconnu")
        rejected("""{"version":1}""", "ni « child » ni « parent »")
    }

    @Test
    fun linkDetection() {
        assertTrue(RemoteConfig.looksLikeLink(" https://config.exemple.fr/k/abc?token=x "))
        assertTrue(!RemoteConfig.looksLikeLink("http://config.exemple.fr"))
        assertTrue(!RemoteConfig.looksLikeLink("kinwaypoint://setup?server=x"))
    }
}
