package br.com.iptvcaseiro

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
}
