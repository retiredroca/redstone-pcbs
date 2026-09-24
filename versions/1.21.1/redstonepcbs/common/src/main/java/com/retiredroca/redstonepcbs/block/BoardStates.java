package com.retiredroca.redstonepcbs.block;

import com.retiredroca.redstonepcbs.chip.Dir;
import com.retiredroca.redstonepcbs.chip.Part;

import net.minecraft.core.Direction;
import net.minecraft.core.FrontAndTop;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ComparatorMode;

/**
 * The board's block palette: maps a {@link Part} plus a facing to the vanilla {@link BlockState} that
 * is placed in the board's backing region. From then on Minecraft owns the state and its behaviour.
 */
public final class BoardStates {
    private BoardStates() {}

    /** The initial state for placing {@code part} facing {@code facing}. */
    public static BlockState initial(Part part, Dir facing) {
        Dir f = part.sanitizeFacing(facing);
        return switch (part) {
            case AIR -> Blocks.AIR.defaultBlockState();
            case SOLID -> Blocks.STONE.defaultBlockState();
            case GLASS -> Blocks.GLASS.defaultBlockState();
            case REDSTONE_BLOCK -> Blocks.REDSTONE_BLOCK.defaultBlockState();
            case NOTE_BLOCK -> Blocks.NOTE_BLOCK.defaultBlockState();
            case LAMP -> Blocks.REDSTONE_LAMP.defaultBlockState();
            case DUST -> Blocks.REDSTONE_WIRE.defaultBlockState();
            case TORCH -> torchState(f);
            case REPEATER -> Blocks.REPEATER.defaultBlockState()
                    .setValue(RepeaterBlock.FACING, Directions.toMinecraft(f));
            case COMPARATOR -> Blocks.COMPARATOR.defaultBlockState()
                    .setValue(ComparatorBlock.FACING, Directions.toMinecraft(f))
                    .setValue(ComparatorBlock.MODE, ComparatorMode.COMPARE);
            case OBSERVER -> Blocks.OBSERVER.defaultBlockState()
                    .setValue(ObserverBlock.FACING, Directions.toMinecraft(f));
            case HOPPER -> Blocks.HOPPER.defaultBlockState()
                    .setValue(HopperBlock.FACING, Directions.toMinecraft(f));
            case LEVER -> Blocks.LEVER.defaultBlockState()
                    .setValue(BlockStateProperties.ATTACH_FACE, attachFace(f))
                    .setValue(HorizontalDirectionalBlock.FACING, attachFacing(f));
            case BUTTON -> Blocks.STONE_BUTTON.defaultBlockState()
                    .setValue(BlockStateProperties.ATTACH_FACE, attachFace(f))
                    .setValue(HorizontalDirectionalBlock.FACING, attachFacing(f));
            case FURNACE -> furnace(Blocks.FURNACE.defaultBlockState(), f);
            case BLAST_FURNACE -> furnace(Blocks.BLAST_FURNACE.defaultBlockState(), f);
            case SMOKER -> furnace(Blocks.SMOKER.defaultBlockState(), f);
            case BREWING_STAND -> Blocks.BREWING_STAND.defaultBlockState();
            case CRAFTER -> crafter(f);
        };
    }

    /** Whether a state belongs to the board palette (or is air); anything else is foreign terrain. */
    public static boolean isBoardBlock(BlockState state) {
        return state.isAir()
                || state.is(Blocks.STONE)
                || state.is(Blocks.GLASS)
                || state.is(Blocks.REDSTONE_BLOCK)
                || state.is(Blocks.NOTE_BLOCK)
                || state.is(Blocks.REDSTONE_LAMP)
                || state.is(Blocks.REDSTONE_WIRE)
                || state.is(Blocks.REDSTONE_TORCH)
                || state.is(Blocks.REDSTONE_WALL_TORCH)
                || state.is(Blocks.REPEATER)
                || state.is(Blocks.COMPARATOR)
                || state.is(Blocks.OBSERVER)
                || state.is(Blocks.HOPPER)
                || state.is(Blocks.LEVER)
                || state.is(Blocks.STONE_BUTTON)
                || state.is(Blocks.FURNACE)
                || state.is(Blocks.BLAST_FURNACE)
                || state.is(Blocks.SMOKER)
                || state.is(Blocks.BREWING_STAND)
                || state.is(Blocks.CRAFTER);
    }

    /** The palette part that maps to a placed state, or AIR. Used by the editor's hover/selection. */
    public static Part partOf(BlockState state) {
        if (state.isAir()) {
            return Part.AIR;
        }
        if (state.is(Blocks.STONE)) return Part.SOLID;
        if (state.is(Blocks.GLASS)) return Part.GLASS;
        if (state.is(Blocks.REDSTONE_BLOCK)) return Part.REDSTONE_BLOCK;
        if (state.is(Blocks.NOTE_BLOCK)) return Part.NOTE_BLOCK;
        if (state.is(Blocks.REDSTONE_LAMP)) return Part.LAMP;
        if (state.is(Blocks.REDSTONE_WIRE)) return Part.DUST;
        if (state.is(Blocks.REDSTONE_TORCH) || state.is(Blocks.REDSTONE_WALL_TORCH)) return Part.TORCH;
        if (state.is(Blocks.REPEATER)) return Part.REPEATER;
        if (state.is(Blocks.COMPARATOR)) return Part.COMPARATOR;
        if (state.is(Blocks.OBSERVER)) return Part.OBSERVER;
        if (state.is(Blocks.HOPPER)) return Part.HOPPER;
        if (state.is(Blocks.LEVER)) return Part.LEVER;
        if (state.is(Blocks.STONE_BUTTON)) return Part.BUTTON;
        if (state.is(Blocks.FURNACE)) return Part.FURNACE;
        if (state.is(Blocks.BLAST_FURNACE)) return Part.BLAST_FURNACE;
        if (state.is(Blocks.SMOKER)) return Part.SMOKER;
        if (state.is(Blocks.BREWING_STAND)) return Part.BREWING_STAND;
        if (state.is(Blocks.CRAFTER)) return Part.CRAFTER;
        return Part.SOLID;
    }

    private static BlockState torchState(Dir facing) {
        if (facing == Dir.DOWN || facing == Dir.UP) {
            return Blocks.REDSTONE_TORCH.defaultBlockState();
        }
        // WallTorchBlock.FACING points away from the wall (opposite our attachment direction).
        return Blocks.REDSTONE_WALL_TORCH.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Directions.toMinecraft(facing.opposite()));
    }

    private static BlockState furnace(BlockState state, Dir facing) {
        return state.setValue(HorizontalDirectionalBlock.FACING, Directions.toMinecraft(facing));
    }

    private static BlockState crafter(Dir facing) {
        Direction front = Directions.toMinecraft(facing);
        Direction top = front.getAxis() == Direction.Axis.Y ? Direction.NORTH : Direction.UP;
        return Blocks.CRAFTER.defaultBlockState()
                .setValue(BlockStateProperties.ORIENTATION, FrontAndTop.fromFrontAndTop(front, top));
    }

    private static AttachFace attachFace(Dir facing) {
        return switch (facing) {
            case DOWN -> AttachFace.FLOOR;
            case UP -> AttachFace.CEILING;
            default -> AttachFace.WALL;
        };
    }

    private static Direction attachFacing(Dir facing) {
        return facing.isHorizontal() ? Directions.toMinecraft(facing.opposite()) : Direction.NORTH;
    }
}
