package com.retiredroca.redstonepcbs.chip;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The serialised board payload. A PCB item carries a player's whole circuit, so a layout change that
 * misreads an old payload does not throw — it silently returns a different grid, and the player finds
 * out by losing their build. Both layouts are pinned here for that reason.
 */
class ChipLayoutTest {

    private static byte[] bytes(int... vals) {
        byte[] out = new byte[vals.length];
        for (int i = 0; i < vals.length; i++) {
            out[i] = (byte) vals[i];
        }
        return out;
    }

    /** A payload in the pre-ports format: [facesLen:4][faces][grid]. */
    private static byte[] oldPayload(byte[] faces, byte[] grid) {
        byte[] out = new byte[4 + faces.length + grid.length];
        out[0] = (byte) (faces.length >>> 24);
        out[1] = (byte) (faces.length >>> 16);
        out[2] = (byte) (faces.length >>> 8);
        out[3] = (byte) faces.length;
        System.arraycopy(faces, 0, out, 4, faces.length);
        System.arraycopy(grid, 0, out, 4 + faces.length, grid.length);
        return out;
    }

    @Test
    void newLayoutRoundTripsAllThreeSections() {
        byte[] faces = bytes(1, 2, 3, 4, 5, 6);
        byte[] grid = bytes(9, 8, 7, 6, 5);
        Map<Integer, PortLink> ports = new LinkedHashMap<>();
        ports.put(11, new PortLink(Dir.NORTH, Dir.SOUTH, PortFlow.IN));
        byte[] portBytes = PortCodec.encode(ports);

        byte[] packed = ChipLayout.pack(faces, portBytes, grid);

        assertTrue(ChipLayout.hasPorts(packed));
        assertArrayEquals(faces, ChipLayout.faces(packed));
        assertArrayEquals(portBytes, ChipLayout.ports(packed));
        assertArrayEquals(grid, ChipLayout.grid(packed));
    }

    @Test
    void emptySectionsRoundTrip() {
        byte[] packed = ChipLayout.pack(new byte[0], new byte[0], new byte[0]);
        assertTrue(ChipLayout.hasPorts(packed));
        assertEquals(0, ChipLayout.faces(packed).length);
        assertEquals(0, ChipLayout.ports(packed).length);
        assertEquals(0, ChipLayout.grid(packed).length);
    }

    @Test
    void nullSectionsAreTreatedAsEmpty() {
        byte[] packed = ChipLayout.pack(null, null, null);
        assertEquals(0, ChipLayout.grid(packed).length);
    }

    /** The regression this format exists to prevent. */
    @Test
    void oldPayloadKeepsItsGridAndReportsNoPorts() {
        byte[] faces = bytes(1, 2, 3);
        byte[] grid = bytes(42, 43, 44, 45, 46, 47, 48);

        byte[] packed = oldPayload(faces, grid);

        assertFalse(ChipLayout.hasPorts(packed), "an old payload must not claim to carry ports");
        assertArrayEquals(faces, ChipLayout.faces(packed));
        assertArrayEquals(new byte[0], ChipLayout.ports(packed));
        assertArrayEquals(grid, ChipLayout.grid(packed), "an old payload's grid must survive intact");
    }

    @Test
    void oldPayloadWithNoFacesStillYieldsItsGrid() {
        byte[] grid = bytes(7, 7, 7, 7);
        byte[] packed = oldPayload(new byte[0], grid);

        assertEquals(0, ChipLayout.faces(packed).length);
        assertArrayEquals(grid, ChipLayout.grid(packed));
    }

    @Test
    void aBareGridIsTreatedAsAllGrid() {
        // Pre-header saves stored the grid with no length prefix at all.
        byte[] bare = bytes(1, 2, 3, 4);
        assertArrayEquals(bare, ChipLayout.grid(bare));
    }

    @Test
    void truncatedPayloadsDoNotThrow() {
        byte[] full = ChipLayout.pack(bytes(1, 2, 3), bytes(4, 5, 6), bytes(7, 8, 9));
        for (int n = 0; n < full.length; n++) {
            byte[] cut = new byte[n];
            System.arraycopy(full, 0, cut, 0, n);
            // Must not throw, and must not invent a longer section than the data holds.
            int f = ChipLayout.faces(cut).length;
            int p = ChipLayout.ports(cut).length;
            int g = ChipLayout.grid(cut).length;
            assertTrue(f <= n && p <= n && g <= n, "section longer than the payload at n=" + n);
        }
    }

    @Test
    void nullIsToleratedEverywhere() {
        assertFalse(ChipLayout.hasPorts(null));
        assertEquals(0, ChipLayout.faces(null).length);
        assertEquals(0, ChipLayout.ports(null).length);
        assertEquals(0, ChipLayout.grid(null).length);
    }
}
