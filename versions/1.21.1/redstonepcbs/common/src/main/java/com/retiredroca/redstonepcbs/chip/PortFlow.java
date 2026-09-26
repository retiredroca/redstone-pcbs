package com.retiredroca.redstonepcbs.chip;

/**
 * Which way a signal crosses a port.
 *
 * <p>Derived from the part in most cases — a source drives out, a sink takes in — but a wire's sides
 * are bidirectional in vanilla, so for those the port carries the direction the player chose rather
 * than one the block implies.
 */
public enum PortFlow {
    /** World to board: the level arriving on the PCB face is served at the port cell. */
    IN,
    /** Board to world: the level at the port cell leaves through the PCB face. */
    OUT;

    public static final PortFlow[] VALUES = values();

    public static PortFlow byOrdinal(int ordinal) {
        return VALUES[Math.floorMod(ordinal, VALUES.length)];
    }
}
