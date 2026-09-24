package com.retiredroca.redstonepcbs.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.retiredroca.redstonepcbs.RedstonePcbs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Simple JSON config at {@code config/redstonepcbs.json}, shared by both loaders. Only the server
 * side reads the values that affect play; the client reads it too when listing blueprints.
 */
public final class PcbsConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("redstonepcbs");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "redstonepcbs.json";

    public static final int DEFAULT_MAX_DESIGNS = 16;
    public static final boolean DEFAULT_ALLOW_IMPORT = true;

    private static boolean loaded;
    private static int maxDesigns = DEFAULT_MAX_DESIGNS;
    private static boolean allowImport = DEFAULT_ALLOW_IMPORT;

    private PcbsConfig() {}

    public static synchronized void load() {
        loaded = true;
        Path file = RedstonePcbs.platform().configDirectory().resolve(FILE_NAME);
        JsonObject root = read(file);
        if (root == null) {
            root = new JsonObject();
        }
        maxDesigns = clampInt(root, "maxDesigns", DEFAULT_MAX_DESIGNS, 1, 4096);
        allowImport = bool(root, "allowImport", DEFAULT_ALLOW_IMPORT);
        write(file, root);
    }

    /** How many saved designs each player may keep. */
    public static int maxDesigns() {
        ensure();
        return maxDesigns;
    }

    /** Whether players may import shared blueprints. */
    public static boolean allowImport() {
        ensure();
        return allowImport;
    }

    private static synchronized void ensure() {
        if (!loaded) {
            load();
        }
    }

    private static JsonObject read(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement element = JsonParser.parseReader(reader);
            return element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (Exception e) {
            LOGGER.warn("redstonepcbs: could not read {}: {}", file, e.toString());
            return null;
        }
    }

    private static void write(Path file, JsonObject root) {
        root.addProperty("maxDesigns", maxDesigns);
        root.addProperty("allowImport", allowImport);
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
        } catch (Exception e) {
            LOGGER.warn("redstonepcbs: could not write {}: {}", file, e.toString());
        }
    }

    private static int clampInt(JsonObject root, String key, int fallback, int min, int max) {
        JsonElement element = root.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return Math.max(min, Math.min(max, element.getAsInt()));
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static boolean bool(JsonObject root, String key, boolean fallback) {
        JsonElement element = root.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return element.getAsBoolean();
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
