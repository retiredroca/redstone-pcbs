package com.retiredroca.redstonepcbs.chip;

/**
 * The cell-index layout of a board grid: {@code index = (y * 16 + z) * 16 + x}, so x is the fastest
 * axis and y the slowest, in a 16x16x16 volume.
 *
 * <p>This is the single definition. {@code BoardSpace} delegates to it, and the ports use it, so the
 * grid, the editor and the redstone bridge cannot disagree about which cell an index names.
 * Minecraft-free, so the layout is unit tested in {@code enginetest}.
 */
public final class CellIndex {
    /** Cells per axis; a board is {@value}^3. */
    public static final int SIZE = 16;
    /** Total cells in a board. */
    public static final int COUNT = SIZE * SIZE * SIZE;

    private CellIndex() {}

    /** Packs 0-based grid coordinates into a cell index. */
    public static int index(int x, int y, int z) {
        return (y * SIZE + z) * SIZE + x;
    }

    public static int xOf(int index) {
        return index & (SIZE - 1);
    }

    public static int yOf(int index) {
        return (index >> 8) & (SIZE - 1);
    }

    public static int zOf(int index) {
        return (index >> 4) & (SIZE - 1);
    }

    public static boolean inGrid(int x, int y, int z) {
        return x >= 0 && x < SIZE && y >= 0 && y < SIZE && z >= 0 && z < SIZE;
    }

    public static boolean valid(int index) {
        return index >= 0 && index < COUNT;
    }

    /**
     * The index one step from {@code index} in {@code dir}, or -1 when that step leaves the grid.
     *
     * <p>The bounds check is on the coordinates, not on the computed index. Validating the index instead
     * wraps: stepping east off the last column computes an index that is still inside 0..COUNT-1 — the
     * cell 16 blocks away on the opposite edge — so a cell near an edge would be judged against an
     * unrelated one.
     */
    public static int neighbour(int index, Dir dir) {
        int x = xOf(index) + dir.dx;
        int y = yOf(index) + dir.dy;
        int z = zOf(index) + dir.dz;
        return inGrid(x, y, z) ? index(x, y, z) : -1;
    }
}
