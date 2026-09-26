package com.retiredroca.redstonepcbs.chip;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The single-cell bridge, both directions, through every byte the persistence layer can hand it.
 *
 * <p>There is no face and no side to permute any more, so the cases that used to earn their keep — every
 * face crossed with every side, a cell holding several ports, two cells sharing a face — are gone with
 * the model. What is left is the part that can still be wrong: the cell index surviving a round trip
 * across the 12-bit boundary, the flow surviving a byte, and rubbish being refused rather than read.
 */
class PortCodecTest {

    @Test
    @DisplayName("both directions survive a round trip")
    void roundTripsFlow() {
        for (PortFlow flow : PortFlow.VALUES) {
            for (int cell : new int[] {0, 1, 5, 255, 256, 257, 1000, 4095}) {
                BoardPort port = new BoardPort(cell, flow);
                assertEquals(port, PortCodec.decode(PortCodec.encode(port)),
                        "cell " + cell + " flow " + flow);
            }
        }
    }

    @Test
    @DisplayName("a board with no bridge encodes to nothing at all")
    void encodesNothingWhenAbsent() {
        assertSame(PortCodec.EMPTY, PortCodec.encode(null));
        assertNull(PortCodec.decode(PortCodec.EMPTY));
        assertNull(PortCodec.decode(null));
    }

    @Test
    @DisplayName("the cell index is read whole across the low/high byte split")
    void encodesCellIndexAsTwelveBits() {
        // 4095 is the largest valid index, and its high nibble is the one that would be lost if the
        // split were a byte boundary rather than a nibble.
        for (int cell : new int[] {255, 256, 4095}) {
            byte[] bytes = PortCodec.encode(new BoardPort(cell, PortFlow.OUT));
            assertEquals(2, bytes.length);
            assertEquals(cell & 0xFF, bytes[0] & 0xFF);
            assertEquals((cell >> 8) & 0x0F, (bytes[1] & 0xFF) >>> 4);
        }
    }

    @Test
    @DisplayName("the flow does not bleed into the cell's high nibble")
    void flowIsConfinedToTheTopNibble() {
        for (PortFlow flow : PortFlow.VALUES) {
            BoardPort decoded = PortCodec.decode(PortCodec.encode(new BoardPort(4095, flow)));
            assertNotNull(decoded);
            assertEquals(4095, decoded.cell(), "flow " + flow + " corrupted the cell index");
            assertSame(flow, decoded.flow());
        }
    }

    @Test
    @DisplayName("a cell outside the grid is refused, and cannot be encoded into one")
    void refusesOutOfGridCell() {
        assertSame(PortCodec.EMPTY, PortCodec.encode(new BoardPort(4096, PortFlow.IN)));
        assertSame(PortCodec.EMPTY, PortCodec.encode(new BoardPort(-1, PortFlow.IN)));
        // The index is 12 bits and the grid is 12 bits, so every byte pair decodes inside the grid and
        // there is no out-of-range value to defend against on the way in. Asserted so that stays true.
        for (int hi = 0; hi < 256; hi++) {
            for (int lo = 0; lo < 256; lo++) {
                BoardPort decoded = PortCodec.decode(new byte[] {(byte) lo, (byte) hi});
                assertNotNull(decoded);
                assertTrue(decoded.cell() >= 0 && decoded.cell() < 4096);
            }
        }
    }

    @Test
    @DisplayName("truncated rubbish is refused rather than guessed at")
    void refusesTruncated() {
        assertNull(PortCodec.decode(new byte[] {0x05}));
        assertNull(PortCodec.decode(new byte[0]));
        // A cell of 0 with a flow is a perfectly good single byte pair; half of one is not.
        byte[] full = PortCodec.encode(new BoardPort(0, PortFlow.OUT));
        assertEquals(full.length, 2);
    }

    @Test
    @DisplayName("the flow is what decides which way the level is read, not the cell")
    void flowSelectsDirection() {
        BoardPort in = new BoardPort(42, PortFlow.IN);
        BoardPort out = new BoardPort(42, PortFlow.OUT);
        assertEquals(42, in.cell());
        assertEquals(42, out.cell());
        assertTrue(in.isInput());
        assertFalse(in.isOutput());
        assertTrue(out.isOutput());
        assertFalse(out.isInput());
    }

    @Test
    @DisplayName("a port needs a flow")
    void requiresFlow() {
        assertThrows(IllegalArgumentException.class, () -> new BoardPort(0, null));
    }

    @Test
    @DisplayName("encoding is byte-exact, so two builds cannot disagree about a board")
    void encodingIsStable() {
        // Guards an accidental format change: a different stride or shift would silently invalidate
        // every saved board while every round-trip test still passed.
        assertArrayEquals(new byte[] {0x00, 0x00}, PortCodec.encode(new BoardPort(0, PortFlow.IN)));
        assertArrayEquals(new byte[] {0x01, 0x01}, PortCodec.encode(new BoardPort(1, PortFlow.OUT)));
        assertArrayEquals(new byte[] {(byte) 0xFF, 0x00},
                PortCodec.encode(new BoardPort(255, PortFlow.IN)));
        // The last cell and the far flow: the case a shift error would truncate.
        assertArrayEquals(new byte[] {(byte) 0xFF, (byte) 0xF1},
                PortCodec.encode(new BoardPort(4095, PortFlow.OUT)));
    }

    private static void assertTrue(boolean condition) {
        org.junit.jupiter.api.Assertions.assertTrue(condition);
    }

    private static void assertFalse(boolean condition) {
        org.junit.jupiter.api.Assertions.assertFalse(condition);
    }
}
