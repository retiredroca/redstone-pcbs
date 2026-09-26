package com.retiredroca.redstonepcbs.chip;

import java.util.Objects;

/**
 * One redstone port: a board cell bridged to one PCB face through one of that cell's own sides.
 *
 * <p>The cell index is the map key in the owning board, so it is not part of the record. A port costs
 * no cell and places nothing in the board — the level is served at the signal-read layer — so a cell
 * may be a port for any of the six faces and several cells may share a face. That is the difference
 * from a gateway attachment, where a face belongs to at most one cell and a cell to at most one face.
 *
 * <p>Kept free of Minecraft imports so it can be unit tested without the game; see {@code enginetest}.
 */
public record PortLink(Dir face, Dir side, PortFlow flow) {

    public PortLink {
        Objects.requireNonNull(face, "face");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(flow, "flow");
    }

    /** The port cell in board coordinates for a 0-based cell index. */
    public static int xOf(int cell) {
        return CellIndex.xOf(cell);
    }

    /** The port cell's y, matching {@code BoardSpace}'s index layout. */
    public static int yOf(int cell) {
        return CellIndex.yOf(cell);
    }

    /** The port cell's z, matching {@code BoardSpace}'s index layout. */
    public static int zOf(int cell) {
        return CellIndex.zOf(cell);
    }

    public boolean isInput() {
        return flow == PortFlow.IN;
    }

    public boolean isOutput() {
        return flow == PortFlow.OUT;
    }
}
