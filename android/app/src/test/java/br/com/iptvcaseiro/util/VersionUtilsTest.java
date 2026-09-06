package br.com.iptvcaseiro.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class VersionUtilsTest {
    @Test public void comparesSemanticVersions() {
        assertTrue(VersionUtils.isNewer("2.0.0", "1.0.1"));
        assertTrue(VersionUtils.isNewer("1.0.10", "1.0.9"));
        assertFalse(VersionUtils.isNewer("v1.0.1", "1.0.1"));
        assertFalse(VersionUtils.isNewer("1.0.0", "1.0.1"));
    }
}
