package com.retiredroca.redstonepcbs.chip;

/**
 * The kind of part occupying a voxel. Ordinals are persisted, so only append (never reorder).
 */
public enum Part {
    AIR,
    SOLID,
    DUST,
    TORCH,
    REPEATER,
    COMPARATOR,
    REDSTONE_BLOCK,
    LEVER,
    BUTTON,
    LAMP,
    OBSERVER,
    NOTE_BLOCK,
    GLASS,
    HOPPER;

    public static final Part[] VALUES = values();

    /** Parts that provide strong power on their own (levers, buttons, blocks of redstone). */
    public boolean isSource() {
        return this == REDSTONE_BLOCK || this == LEVER || this == BUTTON;
    }

    /** Parts that strongly power an adjacent solid block. */
    public boolean isStrongEmitter() {
        return this == TORCH || this == REPEATER || this == COMPARATOR || this == OBSERVER
                || isSource();
    }

    /** Parts whose state change is delayed by a scheduled tick. */
    public boolean isDelayed() {
        return this == TORCH || this == REPEATER || this == COMPARATOR || this == OBSERVER;
    }

    /** Parts that can be manually toggled by a player. */
    public boolean isToggleable() {
        return this == LEVER || this == BUTTON;
    }

    /** Parts that provide a full-block shape (dust/torches can sit on them, torches attach to them). */
    public boolean isFullBlock() {
        return this == SOLID || this == GLASS || this == HOPPER || this == REDSTONE_BLOCK
                || this == LAMP || this == OBSERVER || this == NOTE_BLOCK;
    }

    /** Parts that conduct redstone (can be powered and pass it on). */
    public boolean isConductive() {
        return this == SOLID || this == HOPPER;
    }

    // --- behaviour groups -------------------------------------------------------------------

    /** Parts that can be rotated with the rotate action. */
    public boolean isRotatable() {
        return needsSupport() || isHorizontalOnly() || pointsAtNeighbour();
    }

    /**
     * Parts that must be attached to a neighbouring full block (a torch). Placed facing the block
     * it attaches to, and rotates only between directions that actually have support.
     */
    public boolean needsSupport() {
        return this == TORCH;
    }

    /** Parts that orient only within the horizontal plane (repeater, comparator, observer). */
    public boolean isHorizontalOnly() {
        return this == REPEATER || this == COMPARATOR || this == OBSERVER;
    }

    /**
     * Parts that orient toward the face they were placed against and can face any of the six
     * directions (a hopper), matching vanilla placement.
     */
    public boolean pointsAtNeighbour() {
        return this == HOPPER;
    }

    /**
     * Clamps a facing to the directions this part actually supports, so no code path can build an
     * invalid block state. Torches and hoppers use down + the four sides (never up); repeaters,
     * comparators and observers are horizontal only; other parts ignore facing.
     */
    public Dir sanitizeFacing(Dir facing) {
        Dir f = facing == null ? Dir.DOWN : facing;
        if (needsSupport() || pointsAtNeighbour()) {
            return f == Dir.UP ? Dir.DOWN : f;
        }
        if (isHorizontalOnly()) {
            return f.isHorizontal() ? f : Dir.NORTH;
        }
        return f;
    }

    /** The directions a torch/hopper may face: down plus the four sides. */
    public static final Dir[] SUPPORTED_ORDER = {Dir.DOWN, Dir.NORTH, Dir.SOUTH, Dir.WEST, Dir.EAST};

    public static Part byOrdinal(int ordinal) {
        int i = Math.floorMod(ordinal, VALUES.length);
        return VALUES[i];
    }
}
