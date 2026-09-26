package com.retiredroca.redstonepcbs.chip;

import java.util.Arrays;

/**
 * The byte layout of a serialised board payload, so a portable PCB keeps its grid, its gateway
 * attachments and its redstone ports.
 *
 * <p>New payloads are {@code [MAGIC:4][facesLen:4][faces][portsLen:4][ports][grid]}. Payloads written
 * before ports existed are {@code [facesLen:4][faces][grid]}, with no magic. The marker is what tells
 * the two apart, and it is an exact four-byte comparison rather than a length heuristic: a heuristic
 * that guessed "new" on an old payload would read part of the grid as a ports length and silently
 * corrupt a saved circuit, which is far worse than failing to read old ports.
 *
 * <p>Minecraft-free so both layouts are unit tested in {@code enginetest}.
 */
public final class ChipLayout {
    /** Marks a payload that carries a ports section. */
    static final byte[] MAGIC = {'P', 'C', 'B', 'P'};

    private ChipLayout() {}

    /** Packs the three sections into one payload. */
    public static byte[] pack(byte[] faces, byte[] ports, byte[] grid) {
        byte[] f = faces == null ? new byte[0] : faces;
        byte[] p = ports == null ? new byte[0] : ports;
        byte[] g = grid == null ? new byte[0] : grid;
        byte[] out = new byte[4 + 4 + f.length + 4 + p.length + g.length];
        int at = 0;
        System.arraycopy(MAGIC, 0, out, at, 4);
        at += 4;
        at = putLen(out, at, f.length);
        System.arraycopy(f, 0, out, at, f.length);
        at += f.length;
        at = putLen(out, at, p.length);
        System.arraycopy(p, 0, out, at, p.length);
        at += p.length;
        System.arraycopy(g, 0, out, at, g.length);
        return out;
    }

    /** Whether {@code data} carries the new marker, and so a ports section. */
    public static boolean hasPorts(byte[] data) {
        return data != null && data.length >= 4 && Arrays.equals(MAGIC, Arrays.copyOf(data, 4));
    }

    /** The gateway attachment bytes. Empty when absent or malformed. */
    public static byte[] faces(byte[] data) {
        int at = hasPorts(data) ? 4 : 0;
        int len = lenAt(data, at);
        if (len < 0) {
            return new byte[0];
        }
        at += 4;
        return copy(data, at, len);
    }

    /** The redstone port bytes. Empty when the payload predates ports, or is malformed. */
    public static byte[] ports(byte[] data) {
        if (!hasPorts(data)) {
            return new byte[0];
        }
        int at = 4;
        int faceLen = lenAt(data, at);
        if (faceLen < 0) {
            return new byte[0];
        }
        at += 4 + faceLen;
        int portLen = lenAt(data, at);
        if (portLen < 0) {
            return new byte[0];
        }
        at += 4;
        return copy(data, at, portLen);
    }

    /**
     * The grid bytes: everything after the leading sections. An old payload is just the grid after
     * its faces length, which is why the grid has no length of its own.
     */
    public static byte[] grid(byte[] data) {
        if (data == null) {
            return new byte[0];
        }
        if (!hasPorts(data)) {
            // Old layout: [facesLen:4][faces][grid]. A payload too short to hold a header is a bare
            // grid, which is what pre-header saves contained.
            if (data.length < 4) {
                return data;
            }
            int len = lenAt(data, 0);
            if (len < 0 || len > data.length - 4) {
                return data;
            }
            return copyFrom(data, 4 + len);
        }
        int at = 4;
        int faceLen = lenAt(data, at);
        if (faceLen < 0) {
            return new byte[0];
        }
        at += 4 + faceLen;
        int portLen = lenAt(data, at);
        if (portLen < 0) {
            return new byte[0];
        }
        at += 4 + portLen;
        return copyFrom(data, at);
    }

    /** Reads a big-endian int at {@code at}, or -1 when it would not fit. */
    private static int lenAt(byte[] data, int at) {
        if (data == null || at < 0 || data.length - at < 4) {
            return -1;
        }
        long len = ((long) (data[at] & 0xFF) << 24) | ((data[at + 1] & 0xFF) << 16)
                | ((data[at + 2] & 0xFF) << 8) | (data[at + 3] & 0xFF);
        return len > data.length ? -1 : (int) len;
    }

    /** Copies {@code len} bytes at {@code at}, clamped, so a truncated payload yields less, never throws. */
    private static byte[] copy(byte[] data, int at, int len) {
        if (len <= 0 || at >= data.length) {
            return new byte[0];
        }
        return Arrays.copyOfRange(data, at, Math.min(at + len, data.length));
    }

    /** Copies from {@code at} to the end, clamped, for a section that has no length of its own. */
    private static byte[] copyFrom(byte[] data, int at) {
        if (at >= data.length) {
            return new byte[0];
        }
        return Arrays.copyOfRange(data, Math.max(at, 0), data.length);
    }

    private static int putLen(byte[] out, int at, int len) {
        out[at] = (byte) (len >>> 24);
        out[at + 1] = (byte) (len >>> 16);
        out[at + 2] = (byte) (len >>> 8);
        out[at + 3] = (byte) len;
        return at + 4;
    }
}
