package io.sketch.mochaagents.cli;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SettingsTest {

    @Test void setAndGet() {
        Settings s = new Settings().set("key", "value");
        assertEquals("value", s.get("key"));
    }

    @Test void defaultWhenMissing() {
        assertEquals("default", new Settings().get("nonexistent", "default"));
    }

    @Test void intParsing() {
        Settings s = new Settings().set("timeout", "30000");
        assertEquals(30000, s.getInt("timeout", 0));
        assertEquals(0, s.getInt("bad", 0));
    }

    @Test void boolParsing() {
        assertTrue(new Settings().set("enabled", "true").getBool("enabled", false));
        assertFalse(new Settings().getBool("missing", false));
    }

    @Test void overrideOnReload() {
        Settings s = new Settings().set("key", "first").set("key", "second");
        assertEquals("second", s.get("key"));
    }
}
