package com.retiredroca.redstonepcbs.chip;

/**
 * A board's single redstone bridge: one cell, and which way the signal crosses there.
 *
 * <p>A PCB has at most one of these, and it is either an input or an output, never both. There is no
 * face and no side: the block is powered from whichever of its six neighbours carries the level, and an
 * output is offered to all six, so the port's identity is the cell alone. That removes the question of
 * which world face the editor's frame was showing, which was the main way the bridge could be wired to
 * the wrong side.
 *
 * <p>A port costs no cell and places nothing in the board — the level is served at the signal-read
 * layer — so the designated cell keeps behaving as the component it is.
 *
 * <p>Kept free of Minecraft imports so it can be unit tested without the game; see {@code enginetest}.
 *
 * @param cell the 0-based board cell index that bridges to the world
 * @param flow which way the signal crosses at that cell
 */
public record BoardPort(int cell, PortFlow flow) {

    public BoardPort {
        if (flow == null) {
            throw new IllegalArgumentException("flow");
        }
    }

    public boolean isInput() {
        return flow == PortFlow.IN;
    }

    public boolean isOutput() {
        return flow == PortFlow.OUT;
    }
}
