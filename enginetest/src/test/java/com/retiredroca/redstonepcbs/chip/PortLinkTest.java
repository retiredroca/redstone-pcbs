package com.retiredroca.redstonepcbs.chip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The port record's derived behaviour. */
class PortLinkTest {

    @Test
    void flowDrivesDirection() {
        assertTrue(new PortLink(Dir.UP, Dir.DOWN, PortFlow.IN).isInput());
        assertFalse(new PortLink(Dir.UP, Dir.DOWN, PortFlow.IN).isOutput());
        assertTrue(new PortLink(Dir.UP, Dir.DOWN, PortFlow.OUT).isOutput());
        assertFalse(new PortLink(Dir.UP, Dir.DOWN, PortFlow.OUT).isInput());
    }

    @Test
    void coordinatesMatchTheCellIndex() {
        int cell = CellIndex.index(3, 5, 9);
        assertEquals(3, PortLink.xOf(cell));
        assertEquals(5, PortLink.yOf(cell));
        assertEquals(9, PortLink.zOf(cell));
    }

    @Test
    void flowOrdinalsWrapRatherThanThrow() {
        // Ordinals are persisted, so a downgrade that leaves a stale value must not crash the board.
        assertEquals(PortFlow.IN, PortFlow.byOrdinal(0));
        assertEquals(PortFlow.OUT, PortFlow.byOrdinal(1));
        assertEquals(PortFlow.IN, PortFlow.byOrdinal(2));
        assertEquals(PortFlow.OUT, PortFlow.byOrdinal(-1));
    }

    @Test
    void equalityCoversEveryField() {
        assertEquals(new PortLink(Dir.NORTH, Dir.SOUTH, PortFlow.IN),
                new PortLink(Dir.NORTH, Dir.SOUTH, PortFlow.IN));
        assertFalse(new PortLink(Dir.NORTH, Dir.SOUTH, PortFlow.IN)
                .equals(new PortLink(Dir.SOUTH, Dir.NORTH, PortFlow.IN)));
        assertFalse(new PortLink(Dir.NORTH, Dir.SOUTH, PortFlow.IN)
                .equals(new PortLink(Dir.NORTH, Dir.SOUTH, PortFlow.OUT)));
    }
}
