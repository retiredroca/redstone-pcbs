package com.retiredroca.redstonepcbs.chip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The cell-index layout, pinned. Everything that turns a cell index into a position — the grid, the
 * editor, the gateway, the redstone ports — agrees only if this stays true, so the layout is asserted
 * exhaustively rather than sampled.
 */
class CellIndexTest {

    @Test
    void gridIsSixteenCubed() {
        assertEquals(16, CellIndex.SIZE);
        assertEquals(4096, CellIndex.COUNT);
    }

    @Test
    void everyCellRoundTrips() {
        for (int x = 0; x < CellIndex.SIZE; x++) {
            for (int y = 0; y < CellIndex.SIZE; y++) {
                for (int z = 0; z < CellIndex.SIZE; z++) {
                    int index = CellIndex.index(x, y, z);
                    assertEquals(x, CellIndex.xOf(index), "x at " + x + "," + y + "," + z);
                    assertEquals(y, CellIndex.yOf(index), "y at " + x + "," + y + "," + z);
                    assertEquals(z, CellIndex.zOf(index), "z at " + x + "," + y + "," + z);
                }
            }
        }
    }

    @Test
    void everyIndexInRangeIsValidAndUniquelyAddressesACell() {
        boolean[] seen = new boolean[CellIndex.COUNT];
        for (int index = 0; index < CellIndex.COUNT; index++) {
            assertTrue(CellIndex.valid(index), "index " + index);
            assertTrue(CellIndex.inGrid(CellIndex.xOf(index), CellIndex.yOf(index), CellIndex.zOf(index)));
            assertNotEquals(true, seen[index], "index " + index + " addresses two cells");
            seen[index] = true;
        }
    }

    /**
     * The layout spelled out literally. {@code BoardSpace} used to own this formula and now delegates
     * here, so this is the assertion that the delegation did not quietly change the layout every
     * saved grid, the editor and the gateway already depend on.
     */
    @Test
    void layoutMatchesTheFormulaItReplaced() {
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int index = CellIndex.index(x, y, z);
                    assertEquals((y * 16 + z) * 16 + x, index);
                    assertEquals(index % 16, CellIndex.xOf(index), "x was index % SIZE");
                    assertEquals(index / (16 * 16), CellIndex.yOf(index), "y was index / SIZE^2");
                    assertEquals((index / 16) % 16, CellIndex.zOf(index), "z was (index / SIZE) % SIZE");
                }
            }
        }
    }

    @Test
    void xIsTheFastestAxisAndYTheSlowest() {
        assertEquals(1, CellIndex.index(1, 0, 0) - CellIndex.index(0, 0, 0));
        assertEquals(16, CellIndex.index(0, 0, 1) - CellIndex.index(0, 0, 0));
        assertEquals(256, CellIndex.index(0, 1, 0) - CellIndex.index(0, 0, 0));
    }

    @Test
    void cornersSitAtTheEndsOfTheRange() {
        assertEquals(0, CellIndex.index(0, 0, 0));
        assertEquals(CellIndex.COUNT - 1, CellIndex.index(15, 15, 15));
        assertTrue(CellIndex.inGrid(15, 15, 15));
    }

    @Test
    void outOfGridCoordinatesAreRejected() {
        assertTrue(!CellIndex.inGrid(16, 0, 0));
        assertTrue(!CellIndex.inGrid(0, -1, 0));
        assertTrue(!CellIndex.valid(-1));
        assertTrue(!CellIndex.valid(CellIndex.COUNT));
    }

    /**
     * The regression: validating the computed index instead of the coordinates wrapped to the cell 16
     * blocks away, so an edge cell's neighbour was an unrelated one on the far side of the board.
     */
    @Test
    void steppingOffTheEdgeReportsOffGridRatherThanWrapping() {
        for (Dir dir : Dir.VALUES) {
            assertEquals(-1, CellIndex.neighbour(CellIndex.index(15, 0, 0), Dir.EAST), "east off the edge");
            assertEquals(-1, CellIndex.neighbour(CellIndex.index(0, 0, 0), Dir.WEST), "west off the edge");
            assertEquals(-1, CellIndex.neighbour(CellIndex.index(0, 0, 0), Dir.NORTH), "north off the edge");
            assertEquals(-1, CellIndex.neighbour(CellIndex.index(0, 0, 15), Dir.SOUTH), "south off the edge");
            assertEquals(-1, CellIndex.neighbour(CellIndex.index(0, 0, 0), Dir.DOWN), "down off the edge");
            assertEquals(-1, CellIndex.neighbour(CellIndex.index(0, 15, 0), Dir.UP), "up off the edge");
            // And the same for every cell on the boundary, not just the corners.
            for (int i = 0; i < CellIndex.COUNT; i++) {
                int n = CellIndex.neighbour(i, dir);
                if (n < 0) {
                    continue;
                }
                int deltaX = Math.abs(CellIndex.xOf(n) - CellIndex.xOf(i));
                int deltaY = Math.abs(CellIndex.yOf(n) - CellIndex.yOf(i));
                int deltaZ = Math.abs(CellIndex.zOf(n) - CellIndex.zOf(i));
                int steps = deltaX + deltaY + deltaZ;
                assertEquals(1, steps, dir + " from " + i + " landed " + n + ", more than one step");
            }
        }
    }

    @Test
    void neighbourIsTheOppositeOfNeighbouring() {
        for (int i = 0; i < CellIndex.COUNT; i++) {
            for (Dir dir : Dir.VALUES) {
                int n = CellIndex.neighbour(i, dir);
                if (n >= 0) {
                    assertEquals(i, CellIndex.neighbour(n, dir.opposite()),
                            "stepping " + dir + " then back from " + i);
                }
            }
        }
    }
}
