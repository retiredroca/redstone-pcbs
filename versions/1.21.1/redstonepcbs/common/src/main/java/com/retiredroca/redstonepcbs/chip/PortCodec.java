package com.retiredroca.redstonepcbs.chip;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The signal ports of a board: which PCB face each cell is bridged on, through which of its sides, and
 * in which direction. Encoded as repeating {@code [indexLo][indexHi][face|side|flow]} triples so it
 * rides in the snapshot payload alongside the grid and the gateway attachments without touching either
 * format.
 *
 * <p>One port per cell, keyed by cell index. Unlike a gateway attachment, several cells may share a
 * face and a cell may be ported to any face, because a port costs no cell.
 */
public final class PortCodec {
    public static final byte[] EMPTY = new byte[0];

    /** Bytes per port. */
    private static final int STRIDE = 3;
    /** {@code face+1} and {@code side+1} are 1..6, so a zero byte means "unset". */
    private static final int DIR_SHIFT = 3;
    private static final int DIR_MASK = 0x7;
    private static final int FLOW_SHIFT = 6;

    private PortCodec() {}

    /** Encodes a cell-index to port map as bytes. Ports on an out-of-grid cell are dropped. */
    public static byte[] encode(Map<Integer, PortLink> ports) {
        int n = 0;
        for (Integer cell : ports.keySet()) {
            if (cell != null && CellIndex.valid(cell)) {
                n++;
            }
        }
        byte[] out = new byte[n * STRIDE];
        int p = 0;
        for (Map.Entry<Integer, PortLink> e : ports.entrySet()) {
            int cell = e.getKey();
            if (!CellIndex.valid(cell)) {
                continue;
            }
            PortLink port = e.getValue();
            if (port == null) {
                continue;
            }
            out[p++] = (byte) (cell & 0xFF);
            out[p++] = (byte) ((cell >> 8) & 0x0F);
            out[p++] = (byte) ((port.face().ordinal() + 1)
                    | ((port.side().ordinal() + 1) << DIR_SHIFT)
                    | (port.flow().ordinal() << FLOW_SHIFT));
        }
        return out;
    }

    /** Decodes bytes from {@link #encode}. Tolerates null, empty, truncated and malformed input. */
    public static Map<Integer, PortLink> decode(byte[] data) {
        Map<Integer, PortLink> out = new LinkedHashMap<>();
        if (data == null) {
            return out;
        }
        for (int p = 0; p + STRIDE - 1 < data.length; p += STRIDE) {
            int cell = (data[p] & 0xFF) | ((data[p + 1] & 0x0F) << 8);
            int packed = data[p + 2] & 0xFF;
            int face = packed & DIR_MASK;
            int side = (packed >>> DIR_SHIFT) & DIR_MASK;
            int flow = (packed >>> FLOW_SHIFT) & 0x1;
            // face+1 and side+1 must both be set; a zero in either means the entry is absent.
            if (face == 0 || side == 0 || !CellIndex.valid(cell)) {
                continue;
            }
            out.put(cell, new PortLink(Dir.byOrdinal(face - 1), Dir.byOrdinal(side - 1),
                    PortFlow.byOrdinal(flow)));
        }
        return out;
    }
}
