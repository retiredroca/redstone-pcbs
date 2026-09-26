package com.retiredroca.redstonepcbs.data;

import com.google.gson.JsonObject;

import java.util.Base64;

/**
 * A shareable PCB design: a display name, the serialized grid, its gateway attachments, and its redstone
 * taps, so a shared design carries the same porting as the board it came from.
 */
public record Blueprint(String name, byte[] grid, byte[] faces, byte[] ports) {
    public Blueprint(String name, byte[] grid) {
        this(name, grid, new byte[0], new byte[0]);
    }

    /**
     * 3 added the taps section. A file at 2 has no `ports` key and decodes to no taps, which is why
     * {@link #fromJson} treats the key as optional rather than requiring it.
     */
    public static final int FORMAT = 3;
    /** Upper bound on a grid payload we are willing to read (bytes). */
    public static final int MAX_GRID_BYTES = 1 << 18;

    public JsonObject toJson() {
        JsonObject object = new JsonObject();
        object.addProperty("format", FORMAT);
        object.addProperty("name", name);
        object.addProperty("grid", Base64.getEncoder().encodeToString(grid));
        object.addProperty("faces", Base64.getEncoder().encodeToString(faces));
        object.addProperty("ports", Base64.getEncoder().encodeToString(ports));
        return object;
    }

    /** Reads a blueprint, throwing {@link IllegalArgumentException} on malformed input. */
    public static Blueprint fromJson(JsonObject object) {
        if (!object.has("grid")) {
            throw new IllegalArgumentException("missing grid");
        }
        byte[] data;
        try {
            data = Base64.getDecoder().decode(object.get("grid").getAsString());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("bad grid encoding", e);
        }
        if (data.length == 0 || data.length > MAX_GRID_BYTES) {
            throw new IllegalArgumentException("grid size out of range");
        }
        byte[] faces = new byte[0];
        if (object.has("faces")) {
            try {
                faces = Base64.getDecoder().decode(object.get("faces").getAsString());
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("bad faces encoding", e);
            }
        }
        byte[] ports = new byte[0];
        if (object.has("ports")) {
            try {
                ports = Base64.getDecoder().decode(object.get("ports").getAsString());
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("bad ports encoding", e);
            }
        }
        String name = object.has("name") ? object.get("name").getAsString() : "Imported";
        return new Blueprint(name, data, faces, ports);
    }
}
