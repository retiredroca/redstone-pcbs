package com.retiredroca.redstonepcbs.chip;

/**
 * The redstone port of a board: one cell and a direction, and nothing else.
 *
 * <p>Encoded as {@code [indexLo][indexHi|flow<<4]}, or as an empty array when the board has no port. It
 * rides in the snapshot payload alongside the grid and the gateway attachments without touching either
 * format, so it stays as small as the thing it describes.
 *
 * <p>One board has at most one port, so this is a single record rather than the map the per-face model
 * needed. An out-of-grid cell is refused rather than clamped, on the same reasoning as the rest of the
 * persistence layer: a silently relocated circuit is worse than a missing one.
 */
public final class PortCodec {
    public static final byte[] EMPTY = new byte[0];

    /** Bytes per port. */
    private static final int STRIDE = 2;
    /** The cell index is 12 bits, so byte 1 holds its top nibble and the flow shares the low nibble. */
    private static final int FLOW_MASK = 0x0F;
    private static final int CELL_HIGH_SHIFT = 4;

    private PortCodec() {}

    /** Encodes a port as bytes, or {@link #EMPTY} when there is nothing to encode. */
    public static byte[] encode(BoardPort port) {
        if (port == null || !CellIndex.valid(port.cell())) {
            return EMPTY;
        }
        return new byte[] {
                (byte) (port.cell() & 0xFF),
                (byte) ((((port.cell() >> 8) & FLOW_MASK) << CELL_HIGH_SHIFT)
                        | (port.flow().ordinal() & FLOW_MASK))
        };
    }

    /**
     * Decodes bytes from {@link #encode}, or {@code null} when absent or truncated.
     *
     * <p>Every two-byte value names a cell inside the grid, because the index is 12 bits and the grid is
     * exactly 12 bits wide — so there is no out-of-range case to reject here, and {@code encode} is
     * where a bad index is refused.
     */
    public static BoardPort decode(byte[] data) {
        if (data == null || data.length < STRIDE) {
            return null;
        }
        int cell = (data[0] & 0xFF) | ((data[1] & 0xF0) << 4);
        if (!CellIndex.valid(cell)) {
            return null;
        }
        return new BoardPort(cell, PortFlow.byOrdinal(data[1] & FLOW_MASK));
    }
}
