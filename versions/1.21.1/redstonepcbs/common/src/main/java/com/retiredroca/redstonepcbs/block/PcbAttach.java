package com.retiredroca.redstonepcbs.block;

import com.retiredroca.redstonepcbs.chip.Dir;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The gateway attachments of a board: which PCB face each in-board container cell is exposed on. A
 * face belongs to at most one cell, and a cell to at most one face. This is a tiny sparse map encoded
 * as repeating {@code [indexLo][indexHi][faceOrdinal+1]} triples (12-bit cell index), so it can ride
 * in the snapshot payload alongside the grid bytes without touching the grid format.
 */
public final class PcbAttach {
    public static final byte[] EMPTY = new byte[0];
    /** Packed face value meaning "no attachment". */
    private static final int NONE = 0;

    private PcbAttach() {}

    /** Encodes a cell-index to face map as bytes. */
    public static byte[] encode(Map<Integer, Dir> faces) {
        byte[] out = new byte[faces.size() * 3];
        int p = 0;
        for (Map.Entry<Integer, Dir> e : faces.entrySet()) {
            int index = e.getKey() & 0xFFF;
            out[p++] = (byte) (index & 0xFF);
            out[p++] = (byte) ((index >> 8) & 0x0F);
            out[p++] = (byte) (e.getValue().ordinal() + 1);
        }
        return out;
    }

    /** Decodes bytes from {@link #encode}; tolerates empty/partial input. */
    public static Map<Integer, Dir> decode(byte[] data) {
        Map<Integer, Dir> out = new LinkedHashMap<>();
        if (data == null) {
            return out;
        }
        for (int p = 0; p + 2 < data.length; p += 3) {
            int index = (data[p] & 0xFF) | ((data[p + 1] & 0x0F) << 8);
            int face = data[p + 2] & 0xFF;
            if (face > NONE) {
                out.put(index, Dir.byOrdinal(face - 1));
            }
        }
        return out;
    }
}
