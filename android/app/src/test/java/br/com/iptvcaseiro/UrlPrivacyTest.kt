package br.com.iptvcaseiro

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class UrlPrivacyTest {
    @Test
    fun incompleteUrlTypedByUserDoesNotCrash() {
        assertFalse(isSensitive("http:"))
        assertFalse(isSensitive("https:"))
        assertFalse(isSensitive("http://"))
    }

    @Test
    fun detectsCredentialsOnlyOnValidHierarchicalUrls() {
        assertTrue(isSensitive("https://usuario:senha@exemplo.test/lista.m3u"))
        assertTrue(isSensitive("https://exemplo.test/lista.m3u?token=privado"))
        assertFalse(isSensitive("https://exemplo.test/lista.m3u"))
    }

    @Test
    fun hidesXtreamCredentialsStoredInPath() {
        val source = "https://exemplo.test/live/usuario/secreto/123.ts"
        assertTrue(isSensitive(source))
        assertEquals("https://exemplo.test/live/•••/•••/123.ts", redactSource(source))
        assertFalse(redactSource(source).contains("usuario"))
        assertFalse(redactSource(source).contains("secreto"))
    }
}
