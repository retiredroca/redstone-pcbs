package com.retiredroca.redstonepcbs.block;

import com.retiredroca.redstonepcbs.chip.Cell;
import com.retiredroca.redstonepcbs.chip.Dir;

import net.minecraft.core.Direction;
import net.minecraft.core.FrontAndTop;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BrewingStandBlock;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ComparatorMode;
import net.minecraft.world.level.block.state.properties.RedstoneSide;

/**
 * Maps a chip cell to the vanilla block state it represents. Shared by the server (so the in-board
 * block entities see a correct state) and the client editor (which renders it). The container/
 * processor parts reuse the redstone-agnostic cell fields for their visual state:
 * {@code powered} = furnace lit, {@code on} = crafter crafting, {@code power} = brewing bottle mask.
 */
public final class BoardStates {
    private BoardStates() {}

    public static BlockState of(Cell cell) {
        Dir facing = cell.part.sanitizeFacing(cell.facing);
        return switch (cell.part) {
            case AIR -> Blocks.AIR.defaultBlockState();
            case SOLID -> Blocks.STONE.defaultBlockState();
            case GLASS -> Blocks.GLASS.defaultBlockState();
            case REDSTONE_BLOCK -> Blocks.REDSTONE_BLOCK.defaultBlockState();
            case NOTE_BLOCK -> Blocks.NOTE_BLOCK.defaultBlockState();
            case LAMP -> Blocks.REDSTONE_LAMP.defaultBlockState()
                    .setValue(RedstoneLampBlock.LIT, cell.powered);
            case DUST -> Blocks.REDSTONE_WIRE.defaultBlockState()
                    .setValue(RedStoneWireBlock.POWER, clamp(cell.power))
                    .setValue(RedStoneWireBlock.NORTH, wireSide(cell, 1))
                    .setValue(RedStoneWireBlock.EAST, wireSide(cell, 2))
                    .setValue(RedStoneWireBlock.SOUTH, wireSide(cell, 4))
                    .setValue(RedStoneWireBlock.WEST, wireSide(cell, 8));
            case TORCH -> torchState(facing, cell.powered);
            case REPEATER -> Blocks.REPEATER.defaultBlockState()
                    .setValue(RepeaterBlock.FACING, Directions.toMinecraft(facing))
                    .setValue(RepeaterBlock.DELAY, Math.max(1, Math.min(4, cell.delay + 1)))
                    .setValue(RepeaterBlock.POWERED, cell.powered);
            case COMPARATOR -> Blocks.COMPARATOR.defaultBlockState()
                    .setValue(ComparatorBlock.FACING, Directions.toMinecraft(facing))
                    .setValue(ComparatorBlock.MODE,
                            cell.subtract ? ComparatorMode.SUBTRACT : ComparatorMode.COMPARE)
                    .setValue(ComparatorBlock.POWERED, cell.powered);
            case OBSERVER -> Blocks.OBSERVER.defaultBlockState()
                    .setValue(ObserverBlock.FACING, Directions.toMinecraft(facing))
                    .setValue(ObserverBlock.POWERED, cell.powered);
            case HOPPER -> Blocks.HOPPER.defaultBlockState()
                    .setValue(HopperBlock.FACING, Directions.toMinecraft(facing));
            case LEVER -> Blocks.LEVER.defaultBlockState()
                    .setValue(BlockStateProperties.ATTACH_FACE, attachFace(facing))
                    .setValue(HorizontalDirectionalBlock.FACING, attachFacing(facing))
                    .setValue(BlockStateProperties.POWERED, cell.on);
            case BUTTON -> Blocks.STONE_BUTTON.defaultBlockState()
                    .setValue(BlockStateProperties.ATTACH_FACE, attachFace(facing))
                    .setValue(HorizontalDirectionalBlock.FACING, attachFacing(facing))
                    .setValue(BlockStateProperties.POWERED, cell.on);
            case FURNACE -> furnaceState(Blocks.FURNACE.defaultBlockState(), facing, cell.powered);
            case BLAST_FURNACE -> furnaceState(Blocks.BLAST_FURNACE.defaultBlockState(), facing, cell.powered);
            case SMOKER -> furnaceState(Blocks.SMOKER.defaultBlockState(), facing, cell.powered);
            case BREWING_STAND -> Blocks.BREWING_STAND.defaultBlockState()
                    .setValue(BrewingStandBlock.HAS_BOTTLE[0], (cell.power & 1) != 0)
                    .setValue(BrewingStandBlock.HAS_BOTTLE[1], (cell.power & 2) != 0)
                    .setValue(BrewingStandBlock.HAS_BOTTLE[2], (cell.power & 4) != 0);
            case CRAFTER -> crafterState(facing, cell.on);
        };
    }

    private static BlockState crafterState(Dir facing, boolean crafting) {
        Direction front = Directions.toMinecraft(facing);
        Direction top = front.getAxis() == Direction.Axis.Y ? Direction.NORTH : Direction.UP;
        return Blocks.CRAFTER.defaultBlockState()
                .setValue(BlockStateProperties.ORIENTATION, FrontAndTop.fromFrontAndTop(front, top))
                .setValue(CrafterBlock.CRAFTING, crafting);
    }

    private static AttachFace attachFace(Dir facing) {
        return switch (facing) {
            case DOWN -> AttachFace.FLOOR;
            case UP -> AttachFace.CEILING;
            default -> AttachFace.WALL;
        };
    }

    private static Direction attachFacing(Dir facing) {
        // FaceAttachedHorizontalDirectionalBlock.FACING points away from the supporting wall.
        return facing.isHorizontal() ? Directions.toMinecraft(facing.opposite()) : Direction.NORTH;
    }

    private static BlockState furnaceState(BlockState state, Dir facing, boolean lit) {
        return state
                .setValue(HorizontalDirectionalBlock.FACING, Directions.toMinecraft(facing))
                .setValue(AbstractFurnaceBlock.LIT, lit);
    }

    private static int clamp(int power) {
        return Math.max(0, Math.min(15, power));
    }

    private static BlockState torchState(Dir facing, boolean powered) {
        if (facing == Dir.DOWN || facing == Dir.UP) {
            return Blocks.REDSTONE_TORCH.defaultBlockState()
                    .setValue(RedstoneTorchBlock.LIT, powered);
        }
        // WallTorchBlock.FACING points away from the wall (opposite our attachment direction).
        return Blocks.REDSTONE_WALL_TORCH.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Directions.toMinecraft(facing.opposite()))
                .setValue(RedstoneTorchBlock.LIT, powered);
    }

    private static RedstoneSide wireSide(Cell cell, int bit) {
        if ((cell.dustUpMask & bit) != 0) {
            return RedstoneSide.UP;
        }
        return (cell.dustMask & bit) != 0 ? RedstoneSide.SIDE : RedstoneSide.NONE;
    }
}
