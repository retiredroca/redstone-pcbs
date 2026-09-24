package com.retiredroca.redstonepcbs.block;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ComparatorMode;
import net.minecraft.world.level.block.state.properties.RedstoneSide;

/** State manipulations for the editor, applied to the real blocks in the board region. */
public final class BoardEdit {
    private static final Direction[] OBSERVER_ORDER =
            {Direction.DOWN, Direction.UP, Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
    private static final Direction[] HOPPER_ORDER =
            {Direction.DOWN, Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    private BoardEdit() {}

    public static BlockState rotate(BlockState state) {
        if (state.is(Blocks.REDSTONE_TORCH)) {
            return Blocks.REDSTONE_WALL_TORCH.defaultBlockState()
                    .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
                    .setValue(RedstoneTorchBlock.LIT, state.getValue(RedstoneTorchBlock.LIT));
        }
        if (state.is(Blocks.REDSTONE_WALL_TORCH)) {
            Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
            if (facing == Direction.WEST) {
                return Blocks.REDSTONE_TORCH.defaultBlockState()
                        .setValue(RedstoneTorchBlock.LIT, state.getValue(RedstoneTorchBlock.LIT));
            }
            return state.setValue(BlockStateProperties.HORIZONTAL_FACING, facing.getClockWise());
        }
        if (state.hasProperty(ObserverBlock.FACING)) {
            return state.setValue(ObserverBlock.FACING, next(OBSERVER_ORDER, state.getValue(ObserverBlock.FACING)));
        }
        if (state.hasProperty(HopperBlock.FACING)) {
            Direction facing = state.getValue(HopperBlock.FACING);
            Direction next = next(HOPPER_ORDER, facing);
            return state.setValue(HopperBlock.FACING, next == null ? HOPPER_ORDER[0] : next);
        }
        if (state.hasProperty(RepeaterBlock.FACING)) {
            return state.setValue(RepeaterBlock.FACING, state.getValue(RepeaterBlock.FACING).getClockWise());
        }
        if (state.hasProperty(ComparatorBlock.FACING)) {
            return state.setValue(ComparatorBlock.FACING, state.getValue(ComparatorBlock.FACING).getClockWise());
        }
        if (state.hasProperty(HorizontalDirectionalBlock.FACING)) {
            return state.setValue(HorizontalDirectionalBlock.FACING,
                    state.getValue(HorizontalDirectionalBlock.FACING).getClockWise());
        }
        if (state.hasProperty(BlockStateProperties.ATTACH_FACE)) {
            // lever/button: rotate the horizontal facing
            if (state.hasProperty(HorizontalDirectionalBlock.FACING)) {
                return state.setValue(HorizontalDirectionalBlock.FACING,
                        state.getValue(HorizontalDirectionalBlock.FACING).getClockWise());
            }
        }
        return state;
    }

    public static BlockState cycleDelay(BlockState state) {
        if (state.hasProperty(RepeaterBlock.DELAY)) {
            int delay = state.getValue(RepeaterBlock.DELAY);
            return state.setValue(RepeaterBlock.DELAY, delay >= 4 ? 1 : delay + 1);
        }
        return state;
    }

    public static BlockState toggleComparatorMode(BlockState state) {
        if (state.hasProperty(ComparatorBlock.MODE)) {
            return state.setValue(ComparatorBlock.MODE,
                    state.getValue(ComparatorBlock.MODE) == ComparatorMode.COMPARE
                            ? ComparatorMode.SUBTRACT : ComparatorMode.COMPARE);
        }
        return state;
    }

    /** A vanilla-parity right-click: toggles a lever, presses a button, cycles a repeater delay,
     * toggles a comparator's mode, or shapes an isolated redstone wire. Only the lever/button case
     * changes a powered flag; the rest mutate the in-editor state the same way the game would. */
    public static BlockState interact(BlockState state) {
        if (state.is(Blocks.LEVER) || state.is(Blocks.STONE_BUTTON)) {
            if (state.hasProperty(BlockStateProperties.POWERED)) {
                return state.setValue(BlockStateProperties.POWERED, !state.getValue(BlockStateProperties.POWERED));
            }
        }
        if (state.hasProperty(RepeaterBlock.DELAY)) {
            int delay = state.getValue(RepeaterBlock.DELAY);
            return state.setValue(RepeaterBlock.DELAY, delay >= 4 ? 1 : delay + 1);
        }
        if (state.hasProperty(ComparatorBlock.MODE)) {
            return state.setValue(ComparatorBlock.MODE,
                    state.getValue(ComparatorBlock.MODE) == ComparatorMode.COMPARE
                            ? ComparatorMode.SUBTRACT : ComparatorMode.COMPARE);
        }
        if (state.is(Blocks.REDSTONE_WIRE)) {
            // Vanilla wire use(): an isolated (cross or dot) wire toggles between connected and bare.
            if (isWireCross(state)) {
                return wireConnections(state, RedstoneSide.NONE);
            }
            if (isWireDot(state)) {
                return wireConnections(state, RedstoneSide.SIDE);
            }
        }
        return state;
    }

    /** Whether the wire can be hand-shaped: all four horizontal connections agree (cross or dot). */
    private static boolean isWireCross(BlockState state) {
        return state.getValue(BlockStateProperties.NORTH_REDSTONE).isConnected()
                && state.getValue(BlockStateProperties.SOUTH_REDSTONE).isConnected()
                && state.getValue(BlockStateProperties.EAST_REDSTONE).isConnected()
                && state.getValue(BlockStateProperties.WEST_REDSTONE).isConnected();
    }

    private static boolean isWireDot(BlockState state) {
        return !state.getValue(BlockStateProperties.NORTH_REDSTONE).isConnected()
                && !state.getValue(BlockStateProperties.SOUTH_REDSTONE).isConnected()
                && !state.getValue(BlockStateProperties.EAST_REDSTONE).isConnected()
                && !state.getValue(BlockStateProperties.WEST_REDSTONE).isConnected();
    }

    /** Sets every horizontal wire connection to {@code side}, preserving the current power. */
    private static BlockState wireConnections(BlockState state, RedstoneSide side) {
        return state.setValue(BlockStateProperties.NORTH_REDSTONE, side)
                .setValue(BlockStateProperties.SOUTH_REDSTONE, side)
                .setValue(BlockStateProperties.EAST_REDSTONE, side)
                .setValue(BlockStateProperties.WEST_REDSTONE, side);
    }

    /** The state toggled by a layer pulse (levers flip, buttons press). */
    public static BlockState pulse(BlockState state) {
        if (state.is(Blocks.LEVER) || state.is(Blocks.STONE_BUTTON)) {
            return state.setValue(BlockStateProperties.POWERED, !state.getValue(BlockStateProperties.POWERED));
        }
        return state;
    }

    private static Direction next(Direction[] order, Direction current) {
        for (int i = 0; i < order.length; i++) {
            if (order[i] == current) {
                return order[(i + 1) % order.length];
            }
        }
        return order[0];
    }
}
