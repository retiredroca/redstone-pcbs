package com.retiredroca.redstonepcbs.chip;

/**
 * A direction in the chip's voxel grid. The ordinals are stable and are persisted, so do not
 * reorder them. The set matches the six world faces a board can connect to.
 */
public enum Dir {
    DOWN(0, -1, 0),
    UP(0, 1, 0),
    NORTH(0, 0, -1),
    SOUTH(0, 0, 1),
    WEST(-1, 0, 0),
    EAST(1, 0, 0);

    public static final Dir[] VALUES = values();

    public final int dx;
    public final int dy;
    public final int dz;

    Dir(int dx, int dy, int dz) {
        this.dx = dx;
        this.dy = dy;
        this.dz = dz;
    }

    public Dir opposite() {
        return switch (this) {
            case DOWN -> UP;
            case UP -> DOWN;
            case NORTH -> SOUTH;
            case SOUTH -> NORTH;
            case WEST -> EAST;
            case EAST -> WEST;
        };
    }

    public boolean isHorizontal() {
        return this == NORTH || this == SOUTH || this == WEST || this == EAST;
    }

    public boolean isVertical() {
        return this == DOWN || this == UP;
    }

    /** The horizontal directions perpendicular to this horizontal direction. */
    public Dir[] perpendicularHorizontal() {
        return switch (this) {
            case NORTH, SOUTH -> new Dir[]{WEST, EAST};
            case WEST, EAST -> new Dir[]{NORTH, SOUTH};
            default -> new Dir[0];
        };
    }

    public static Dir byOrdinal(int ordinal) {
        return VALUES[Math.floorMod(ordinal, VALUES.length)];
    }
}
