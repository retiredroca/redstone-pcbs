package com.retiredroca.redstonepcbs.block;

import com.retiredroca.redstonepcbs.chip.Dir;

import net.minecraft.core.Direction;

/** Conversion between Minecraft's {@link Direction} and the chip engine's {@link Dir}. */
public final class Directions {
    private Directions() {}

    public static Dir toChip(Direction direction) {
        return switch (direction) {
            case DOWN -> Dir.DOWN;
            case UP -> Dir.UP;
            case NORTH -> Dir.NORTH;
            case SOUTH -> Dir.SOUTH;
            case WEST -> Dir.WEST;
            case EAST -> Dir.EAST;
        };
    }

    public static Direction toMinecraft(Dir dir) {
        return switch (dir) {
            case DOWN -> Direction.DOWN;
            case UP -> Direction.UP;
            case NORTH -> Direction.NORTH;
            case SOUTH -> Direction.SOUTH;
            case WEST -> Direction.WEST;
            case EAST -> Direction.EAST;
        };
    }
}
