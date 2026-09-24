package com.retiredroca.redstonepcbs.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.retiredroca.redstonepcbs.RedstonePcbs;
import com.retiredroca.redstonepcbs.data.Blueprint;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** Client-side read/write of shared blueprint files under {@code <game>/redstonepcbs/blueprints}. */
public final class BlueprintFiles {
    public static final String FOLDER = "redstonepcbs/blueprints";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private BlueprintFiles() {}

    public static Path folder() {
        Path dir = RedstonePcbs.platform().gameDirectory().resolve(FOLDER);
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {
        }
        return dir;
    }

    /** Exports a design and returns the file written. */
    public static Path write(String name, byte[] grid) throws IOException {
        Path dir = folder();
        String base = sanitize(name);
        Path file = dir.resolve(base + ".json");
        for (int n = 1; Files.exists(file); n++) {
            file = dir.resolve(base + " (" + n + ").json");
        }
        Files.writeString(file, GSON.toJson(new Blueprint(name, grid).toJson()), StandardCharsets.UTF_8);
        return file;
    }

    public static List<Path> list() {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(folder())) {
            stream.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                    .sorted()
                    .forEach(files::add);
        } catch (IOException ignored) {
        }
        return files;
    }

    public static Blueprint read(Path file) throws IOException {
        JsonElement element = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
        if (!element.isJsonObject()) {
            throw new IOException("not a blueprint");
        }
        return Blueprint.fromJson(element.getAsJsonObject());
    }

    private static String sanitize(String name) {
        String base = name == null ? "" : name.strip();
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < base.length() && out.length() < 48; i++) {
            char c = base.charAt(i);
            out.append(isValid(c) ? c : '_');
        }
        String result = out.toString().strip();
        return result.isEmpty() ? "design" : result;
    }

    private static boolean isValid(char c) {
        return Character.isLetterOrDigit(c) || c == ' ' || c == '-' || c == '_' || c == '.';
    }
}
