package com.retiredroca.redstonepcbs.chip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The port wire format. Ports are persisted and echoed in the snapshot, so a bad round trip silently
 * moves a player's bridge to the wrong cell or the wrong face — the kind of corruption that only shows
 * up as "my redstone stopped working" much later.
 */
class PortCodecTest {

    @Test
    void emptyEncodesToEmpty() {
        assertEquals(0, PortCodec.encode(Map.of()).length);
        assertTrue(PortCodec.decode(null).isEmpty());
        assertTrue(PortCodec.decode(PortCodec.EMPTY).isEmpty());
    }

    @Test
    void singlePortRoundTrips() {
        Map<Integer, PortLink> ports = new LinkedHashMap<>();
        ports.put(42, new PortLink(Dir.EAST, Dir.NORTH, PortFlow.IN));

        Map<Integer, PortLink> back = PortCodec.decode(PortCodec.encode(ports));

        assertEquals(1, back.size());
        assertEquals(new PortLink(Dir.EAST, Dir.NORTH, PortFlow.IN), back.get(42));
    }

    @Test
    void everyFaceSideAndFlowCombinationRoundTrips() {
        for (Dir face : Dir.VALUES) {
            for (Dir side : Dir.VALUES) {
                for (PortFlow flow : PortFlow.VALUES) {
                    Map<Integer, PortLink> ports = Map.of(7, new PortLink(face, side, flow));
                    PortLink back = PortCodec.decode(PortCodec.encode(ports)).get(7);
                    assertNotNull(back, face + "/" + side + "/" + flow + " was dropped");
                    assertEquals(face, back.face());
                    assertEquals(side, back.side());
                    assertEquals(flow, back.flow());
                }
            }
        }
    }

    /** The 12-bit index packing must hold for every cell, not just a sample. */
    @Test
    void everyCellIndexRoundTrips() {
        for (int cell = 0; cell < CellIndex.COUNT; cell++) {
            Map<Integer, PortLink> ports = Map.of(cell, new PortLink(Dir.UP, Dir.DOWN, PortFlow.OUT));
            Map<Integer, PortLink> back = PortCodec.decode(PortCodec.encode(ports));
            assertEquals(1, back.size(), "cell " + cell);
            assertEquals(new PortLink(Dir.UP, Dir.DOWN, PortFlow.OUT), back.get(cell),
                    "cell " + cell);
        }
    }

    @Test
    void everyCellAtMaximumPackingRoundTrips() {
        // Worst case for the byte layout: index 4095 and the largest face/side/flow values.
        int last = CellIndex.COUNT - 1;
        Map<Integer, PortLink> ports = Map.of(last, new PortLink(Dir.EAST, Dir.EAST, PortFlow.OUT));
        assertEquals(ports, PortCodec.decode(PortCodec.encode(ports)));
    }

    @Test
    void severalCellsMayShareAFace() {
        // A port costs no cell, so unlike a gateway attachment a face is not exclusive.
        Map<Integer, PortLink> ports = new LinkedHashMap<>();
        ports.put(0, new PortLink(Dir.UP, Dir.DOWN, PortFlow.IN));
        ports.put(1, new PortLink(Dir.UP, Dir.DOWN, PortFlow.IN));
        ports.put(4095, new PortLink(Dir.UP, Dir.NORTH, PortFlow.IN));

        assertEquals(ports, PortCodec.decode(PortCodec.encode(ports)));
    }

    @Test
    void orderIsPreserved() {
        Map<Integer, PortLink> ports = new LinkedHashMap<>();
        ports.put(9, new PortLink(Dir.DOWN, Dir.DOWN, PortFlow.IN));
        ports.put(3, new PortLink(Dir.DOWN, Dir.DOWN, PortFlow.IN));
        ports.put(100, new PortLink(Dir.DOWN, Dir.DOWN, PortFlow.IN));

        assertEquals("[9, 3, 100]", PortCodec.decode(PortCodec.encode(ports)).keySet().toString());
    }

    @Test
    void outOfGridCellsAreDropped() {
        Map<Integer, PortLink> ports = new LinkedHashMap<>();
        ports.put(5, new PortLink(Dir.UP, Dir.DOWN, PortFlow.IN));
        ports.put(-1, new PortLink(Dir.UP, Dir.DOWN, PortFlow.IN));
        ports.put(CellIndex.COUNT, new PortLink(Dir.UP, Dir.DOWN, PortFlow.IN));

        Map<Integer, PortLink> back = PortCodec.decode(PortCodec.encode(ports));

        assertEquals(1, back.size());
        assertTrue(back.containsKey(5));
    }

    @Test
    void nullPortsAreSkipped() {
        Map<Integer, PortLink> ports = new LinkedHashMap<>();
        ports.put(5, null);
        ports.put(6, new PortLink(Dir.UP, Dir.DOWN, PortFlow.IN));

        Map<Integer, PortLink> back = PortCodec.decode(PortCodec.encode(ports));

        assertEquals(1, back.size());
        assertTrue(back.containsKey(6));
    }

    @Test
    void onePortIsThreeBytes() {
        // The stride is part of the persisted format, so it is pinned rather than assumed.
        assertEquals(3, PortCodec.encode(Map.of(1, new PortLink(Dir.UP, Dir.DOWN, PortFlow.IN))).length);
        assertEquals(6, PortCodec.encode(Map.of(1, new PortLink(Dir.UP, Dir.DOWN, PortFlow.IN),
                2, new PortLink(Dir.UP, Dir.DOWN, PortFlow.IN))).length);
    }

    @Test
    void truncatedInputIsTolerated() {
        // Built by the encoder, so the bytes are known-good and only the length is under test.
        byte[] valid = PortCodec.encode(Map.of(1, new PortLink(Dir.UP, Dir.DOWN, PortFlow.IN),
                2, new PortLink(Dir.UP, Dir.DOWN, PortFlow.IN)));
        assertEquals(6, valid.length);

        // Cut mid-triple: only the complete first port survives.
        assertEquals(1, PortCodec.decode(java.util.Arrays.copyOf(valid, 5)).size());
        assertEquals(1, PortCodec.decode(java.util.Arrays.copyOf(valid, 3)).size());
        assertEquals(0, PortCodec.decode(java.util.Arrays.copyOf(valid, 2)).size());

        // A stray byte cannot form a triple.
        assertEquals(0, PortCodec.decode(new byte[] {7}).size());
    }

    @Test
    void anEntryWithNoSideIsTreatedAsAbsent() {
        // face+1 set but side+1 zero: a half-written entry must not invent a bridge.
        byte[] faceOnly = new byte[] {1, 0, (byte) (Dir.UP.ordinal() + 1)};
        assertTrue(PortCodec.decode(faceOnly).isEmpty());
    }
}
