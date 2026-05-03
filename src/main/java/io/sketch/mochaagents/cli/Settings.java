package io.sketch.mochaagents.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Hierarchical settings — multi-source config overlay.
 * Pattern from claude-code's settings.ts: userSettings < projectSettings < localSettings < envFlags.
 * @author lanxia39@163.com
 */
public final class Settings {

    private static final Logger log = LoggerFactory.getLogger(Settings.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Map<String, Object> data = new LinkedHashMap<>();

    /** Load settings from a JSON file. Later loads override earlier ones. */
    public Settings load(Path file) {
        if (!Files.exists(file)) return this;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> loaded = JSON.readValue(file.toFile(), Map.class);
            data.putAll(loaded);
            log.debug("Loaded settings from {}", file);
        } catch (IOException e) { log.warn("Failed to load settings: {}", file, e.getMessage()); }
        return this;
    }

    /** Load from environment variable overrides (PREFIX_KEY=value format). */
    public Settings loadEnv(String prefix) {
        System.getenv().forEach((k, v) -> {
            if (k.startsWith(prefix + "_")) {
                String key = k.substring(prefix.length() + 1).toLowerCase();
                data.put(key, v);
            }
        });
        return this;
    }

    /** Set a value programmatically. */
    public Settings set(String key, Object value) { data.put(key, value); return this; }

    /** Get a string value. */
    public String get(String key) { return data.getOrDefault(key, "").toString(); }
    public String get(String key, String def) { Object v = data.get(key); return v != null ? v.toString() : def; }

    /** Get an integer value. */
    public int getInt(String key, int def) {
        try { return Integer.parseInt(get(key)); } catch (NumberFormatException e) { return def; }
    }

    /** Get a boolean value. */
    public boolean getBool(String key, boolean def) {
        String v = get(key); return v.isEmpty() ? def : Boolean.parseBoolean(v);
    }

    /** All key-value pairs. */
    public Map<String, Object> all() { return Collections.unmodifiableMap(data); }

    /** Standard load chain: project → local → env. */
    public static Settings standard() {
        return new Settings()
                .load(Paths.get(".mocha", "settings.json"))
                .load(Paths.get(".mocha", "settings.local.json"))
                .loadEnv("MOCHA");
    }
}
