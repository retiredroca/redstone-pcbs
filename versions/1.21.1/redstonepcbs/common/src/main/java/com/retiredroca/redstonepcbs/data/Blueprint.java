package com.retiredroca.redstonepcbs.data;

import com.google.gson.JsonObject;

import java.util.Base64;

/** A shareable PCB design: a display name plus the serialized grid, as JSON. */
public record Blueprint(String name, byte[] grid) {
    public static final int FORMAT = 1;
    /** Upper bound on a grid payload we are willing to read (bytes). */
    public static final int MAX_GRID_BYTES = 1 << 18;

    public JsonObject toJson() {
        JsonObject object = new JsonObject();
        object.addProperty("format", FORMAT);
        object.addProperty("name", name);
        object.addProperty("grid", Base64.getEncoder().encodeToString(grid));
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
        String name = object.has("name") ? object.get("name").getAsString() : "Imported";
        return new Blueprint(name, data);
    }
}
