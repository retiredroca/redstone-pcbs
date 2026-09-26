package com.retiredroca.redstonepcbs.chip;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The two-tap persistence format, and the two-byte single-tap layout it must keep reading so boards
 * saved before the second tap existed are not thrown away.
 */
class PortCodecTest {

    @Test
    @DisplayName("both taps survive a round trip independently")
    void roundTripsBoth() {
        for (int in : new int[] {BoardTaps.NONE, 0, 1, 255, 256, 257, 1000, 4095}) {
            for (int out : new int[] {BoardTaps.NONE, 0, 7, 256, 4095}) {
                if (in == out && in != BoardTaps.NONE) {
                    continue;
                }
                BoardTaps taps = new BoardTaps(in, out);
                assertEquals(taps, PortCodec.decode(PortCodec.encode(taps)),
                        "in " + in + " out " + out);
            }
        }
    }

    @Test
    @DisplayName("no taps encode to four absent bytes, and back to empty")
    void emptyRoundTrips() {
        byte[] bytes = PortCodec.encode(BoardTaps.EMPTY);
        assertEquals(4, bytes.length);
        assertArrayEquals(new byte[] {-1, -1, -1, -1}, bytes);
        assertTrue(PortCodec.decode(bytes).isEmpty());
        assertTrue(PortCodec.decode(PortCodec.EMPTY).isEmpty());
        assertTrue(PortCodec.decode(null).isEmpty());
    }

    @Test
    @DisplayName("a single tap is not the same as two taps")
    void oneIsNotTwo() {
        BoardTaps onlyIn = PortCodec.decode(PortCodec.encode(new BoardTaps(10, BoardTaps.NONE)));
        assertTrue(onlyIn.hasIn());
        assertFalse(onlyIn.hasOut());
        BoardTaps onlyOut = PortCodec.decode(PortCodec.encode(new BoardTaps(BoardTaps.NONE, 10)));
        assertFalse(onlyOut.hasIn());
        assertTrue(onlyOut.hasOut());
    }

    @Test
    @DisplayName("the cell index is read whole across the two-byte split")
    void readsCellAcrossByteSplit() {
        for (int index : new int[] {0, 1, 255, 256, 4095}) {
            byte[] bytes = PortCodec.encode(new BoardTaps(index, BoardTaps.NONE));
            assertEquals(index & 0xFF, bytes[0] & 0xFF);
            assertEquals(index >>> 8, bytes[1] & 0xFF);
            assertEquals(index, PortCodec.decode(bytes).cellOf(PortFlow.IN));
        }
    }

    @Test
    @DisplayName("a cell outside the grid is refused on both sides")
    void refusesOutOfGridCell() {
        // Encoding drops the out-of-range tap and keeps the valid one beside it, rather than losing both.
        BoardTaps survived = PortCodec.decode(PortCodec.encode(new BoardTaps(4096, 20)));
        assertFalse(survived.hasIn());
        assertEquals(20, survived.cellOf(PortFlow.OUT));
        BoardTaps keptIn = PortCodec.decode(PortCodec.encode(new BoardTaps(20, 4096)));
        assertTrue(keptIn.hasIn());
        assertFalse(keptIn.hasOut(), "the out-of-range output should have been dropped");
        // Hand-built: 0x1000 has a bit set above the grid's 12, so it is not a cell.
        assertTrue(PortCodec.decode(new byte[] {0x00, 0x10, (byte) 0xFF, (byte) 0xFF}).isEmpty());
    }

    @Test
    @DisplayName("the two-byte single-tap layout still decodes into the right direction")
    void readsLegacySingleTap() {
        // The layout that shipped first: [cellLo][cellHi|flow<<4].
        byte[] twoBytes = legacy(1234, PortFlow.IN);
        BoardTaps decoded = PortCodec.decode(twoBytes);
        assertTrue(decoded.hasIn(), "a legacy input should still be an input");
        assertFalse(decoded.hasOut());
        assertEquals(1234, decoded.cellOf(PortFlow.IN));
    }

    @Test
    @DisplayName("a legacy OUTPUT tap stays an output, not an input")
    void readsLegacyOutputDirection() {
        BoardTaps decoded = PortCodec.decode(legacy(10, PortFlow.OUT));
        assertTrue(decoded.hasOut(), "the legacy flow bit must decide the direction");
        assertFalse(decoded.hasIn());
        assertEquals(10, decoded.cellOf(PortFlow.OUT));
    }

    @Test
    @DisplayName("truncated rubbish is refused rather than guessed at")
    void refusesTruncated() {
        assertTrue(PortCodec.decode(new byte[] {0x05}).isEmpty());
        assertTrue(PortCodec.decode(new byte[0]).isEmpty());
        // Two bytes is the legacy single-tap layout and is deliberately readable, not truncated rubbish.
    }

    @Test
    @DisplayName("a corrupt payload naming one cell twice is kept as one tap, not thrown")
    void survivesDuplicateCell() {
        // BoardTaps' own constructor refuses both directions on one cell, so the decoder must not
        // construct one -- a damaged save should lose a direction, not fail to load.
        BoardTaps decoded = PortCodec.decode(new byte[] {0x05, 0x00, 0x05, 0x00});
        assertNotNull(decoded);
        assertEquals(1, (decoded.hasIn() ? 1 : 0) + (decoded.hasOut() ? 1 : 0));
        assertTrue(decoded.hasIn() || decoded.hasOut());
    }

    /** Builds the two-byte single-tap layout as it actually shipped: cell in 12 bits, flow in the low nibble. */
    private static byte[] legacy(int cell, PortFlow flow) {
        return new byte[] {
                (byte) (cell & 0xFF),
                (byte) ((((cell >> 8) & 0x0F) << 4) | (flow.ordinal() & 0x0F))
        };
    }

    @Test
    @DisplayName("encoding is byte-exact, so two builds cannot disagree about a board")
    void encodingIsStable() {
        assertArrayEquals(new byte[] {0x00, 0x00, (byte) 0xFF, (byte) 0xFF},
                PortCodec.encode(new BoardTaps(0, BoardTaps.NONE)));
        assertArrayEquals(new byte[] {(byte) 0xFF, (byte) 0x0F, (byte) 0xFF, (byte) 0xFF},
                PortCodec.encode(new BoardTaps(4095, BoardTaps.NONE)));
        assertArrayEquals(new byte[] {0x01, 0x00, 0x02, 0x00},
                PortCodec.encode(new BoardTaps(1, 2)));
    }

    @Test
    @DisplayName("a decoded payload always obeys the model's own invariant")
    void decodedNeverViolatesTheInvariant() {
        for (int lo = 0; lo < 256; lo++) {
            for (int hi = 0; hi < 256; hi++) {
                BoardTaps decoded = PortCodec.decode(
                        new byte[] {(byte) lo, (byte) hi, (byte) lo, (byte) hi});
                assertNotNull(decoded);
                assertFalse(decoded.hasIn() && decoded.cellOf(PortFlow.IN) == decoded.cellOf(PortFlow.OUT),
                        "both directions on cell " + lo + "," + hi);
            }
        }
    }

    @Test
    @DisplayName("the empty constant is shared, so an absent payload is a true singleton")
    void emptyConstant() {
        assertSame(BoardTaps.EMPTY, PortCodec.decode(PortCodec.EMPTY));
    }
}
