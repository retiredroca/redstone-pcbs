package com.retiredroca.redstonepcbs.chip;

/**
 * A board's two redstone taps: at most one carrying in, at most one carrying out, both able to exist at
 * the same time on different cells.
 *
 * <p>Exactly one of each, and the two are independent. A single tap that was either an input or an output
 * was the earlier model, and it was wrong: it made a block that takes a signal and also drives one
 * impossible to build, because assigning the second direction silently overwrote the first. The
 * invariant is expressed here rather than at each call site, so a cell cannot end up holding both
 * directions or two of the same.
 *
 * <p>Assigning a direction that another cell already holds <em>moves</em> it. That is the intended
 * behaviour — re-pointing a tap should be a single press — but it means a press can relocate a tap that
 * was working, so the editor reports which cell lost one rather than letting it pass unnoticed.
 *
 * <p>Cells are 0-based board indices, or {@link #NONE} for absent. Deliberately Minecraft-free so the
 * whole model is unit tested in {@code enginetest}; whether a cell may hold a tap at all is
 * {@code TapCell}'s rule and is not representable here.
 *
 * @param inCell the cell whose component receives the level from the world, or {@link #NONE}
 * @param outCell the cell whose component drives the level into the world, or {@link #NONE}
 */
public record BoardTaps(int inCell, int outCell) {

    /** The value standing for "no tap in this direction". */
    public static final int NONE = -1;

    /** No taps at all, which is a fresh board and also what clearing both produces. */
    public static final BoardTaps EMPTY = new BoardTaps(NONE, NONE);

    public BoardTaps {
        if (inCell == outCell && inCell != NONE) {
            throw new IllegalArgumentException(
                    "a cell cannot carry both directions: " + inCell);
        }
    }

    /** A single tap, in one direction, which is also the shape a one-tap save decodes to. */
    public static BoardTaps of(PortFlow flow, int cell) {
        return switch (flow) {
            case IN -> new BoardTaps(cell, NONE);
            case OUT -> new BoardTaps(NONE, cell);
        };
    }

    public boolean hasIn() {
        return inCell != NONE;
    }

    public boolean hasOut() {
        return outCell != NONE;
    }

    public boolean isEmpty() {
        return inCell == NONE && outCell == NONE;
    }

    /** The tapped cell for {@code flow}, or {@link #NONE}. */
    public int cellOf(PortFlow flow) {
        return flow == PortFlow.IN ? inCell : outCell;
    }

    /** The flow {@code cell} is tapped in, or {@code null} if it holds no tap. */
    public PortFlow flowAt(int cell) {
        if (cell == NONE) {
            return null;
        }
        if (cell == inCell) {
            return PortFlow.IN;
        }
        return cell == outCell ? PortFlow.OUT : null;
    }

    /**
     * Taps {@code cell} in {@code flow}, or clears that direction when {@code cell} is {@link #NONE}.
     *
     * <p>Because each direction has one slot, this displaces whatever held it. A tap on the same cell in
     * the other direction is released first, so the result can never put both on one cell.
     */
    public BoardTaps with(PortFlow flow, int cell) {
        if (flow == PortFlow.IN) {
            return cell == outCell ? new BoardTaps(cell, NONE) : new BoardTaps(cell, outCell);
        }
        return cell == inCell ? new BoardTaps(NONE, cell) : new BoardTaps(inCell, cell);
    }

    /** Releases whichever tap sits on {@code cell}, or returns this unchanged if it holds none. */
    public BoardTaps cleared(int cell) {
        if (cell == inCell) {
            return new BoardTaps(NONE, outCell);
        }
        return cell == outCell ? new BoardTaps(inCell, NONE) : this;
    }
}
