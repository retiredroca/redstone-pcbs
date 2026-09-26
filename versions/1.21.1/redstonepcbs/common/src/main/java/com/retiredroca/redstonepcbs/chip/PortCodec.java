package com.retiredroca.redstonepcbs.chip;

/**
 * The redstone taps of a board: one cell carrying in, one carrying out.
 *
 * <p>Encoded as four bytes, {@code [inLo][inHi][outLo][outHi]}, with {@code 0xFFFF} meaning "no tap in
 * that direction". It rides in the snapshot payload alongside the grid and the gateway attachments
 * without touching either format.
 *
 * <p><b>Two-byte payloads are still read.</b> The single-tap layout was
 * {@code [cellLo][cellHi|flow<<4]}, and {@link #decode} branches on the section length: two bytes is that
 * older layout, and the flow nibble decides whether the tap is the input or the output. This is a length
 * branch rather than a new {@code ChipLayout} marker, because {@code ChipLayout} hands the section over by
 * explicit length, so two bytes cannot be mistaken for four -- and a third marker would have thrown away
 * every board saved with the second format for no gain. New payloads are always four bytes.
 *
 * <p>A cell outside the grid is refused rather than clamped, on the same reasoning as the rest of the
 * persistence layer: a silently relocated circuit is worse than a missing one.
 */
public final class PortCodec {
    public static final byte[] EMPTY = new byte[0];

    /** Bytes for the current two-tap layout. */
    private static final int STRIDE = 4;
    /** Bytes for the single-tap layout that preceded it. */
    private static final int LEGACY_STRIDE = 2;
    /** A cell index of 16 bits all ones, which no real cell can be. */
    private static final int ABSENT = 0xFFFF;
    /** The legacy layout packs the cell's top nibble above the flow, both inside byte 1. */
    private static final int LEGACY_CELL_HIGH_MASK = 0xF0;
    private static final int LEGACY_FLOW_MASK = 0x0F;

    private PortCodec() {}

    /** Encodes the taps as four bytes. No tap in a direction encodes as absent. */
    public static byte[] encode(BoardTaps taps) {
        BoardTaps t = taps == null ? BoardTaps.EMPTY : taps;
        int in = cell(t.cellOf(PortFlow.IN));
        int out = cell(t.cellOf(PortFlow.OUT));
        return new byte[] {
                (byte) (in & 0xFF), (byte) (in >>> 8),
                (byte) (out & 0xFF), (byte) (out >>> 8)
        };
    }

    /**
     * Decodes bytes from {@link #encode}, accepting the two-byte single-tap layout as well.
     *
     * <p>Returns {@link BoardTaps#EMPTY} for absent, truncated or malformed input rather than throwing,
     * so a damaged item loses its taps instead of refusing to load.
     */
    public static BoardTaps decode(byte[] data) {
        if (data == null) {
            return BoardTaps.EMPTY;
        }
        if (data.length >= STRIDE) {
            int in = index(data[0], data[1]);
            int out = index(data[2], data[3]);
            if (in == BoardTaps.NONE && out == BoardTaps.NONE) {
                return BoardTaps.EMPTY;
            }
            // A corrupt payload naming one cell twice would otherwise throw; treat it as one tap.
            if (in == out) {
                return BoardTaps.of(PortFlow.IN, in);
            }
            return new BoardTaps(in, out);
        }
        if (data.length >= LEGACY_STRIDE) {
            int cell = (data[0] & 0xFF) | ((data[1] & LEGACY_CELL_HIGH_MASK) << 4);
            if (!CellIndex.valid(cell)) {
                return BoardTaps.EMPTY;
            }
            return BoardTaps.of(PortFlow.byOrdinal(data[1] & LEGACY_FLOW_MASK), cell);
        }
        return BoardTaps.EMPTY;
    }

    private static int cell(int index) {
        return CellIndex.valid(index) ? index : ABSENT;
    }

    /** A 16-bit little-endian cell index, or {@link BoardTaps#NONE} for absent or out of the grid. */
    private static int index(byte lo, byte hi) {
        int value = (lo & 0xFF) | ((hi & 0xFF) << 8);
        if (value == ABSENT || !CellIndex.valid(value)) {
            return BoardTaps.NONE;
        }
        return value;
    }
}
