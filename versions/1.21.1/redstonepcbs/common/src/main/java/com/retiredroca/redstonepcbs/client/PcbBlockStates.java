package com.retiredroca.redstonepcbs.client;

import com.retiredroca.redstonepcbs.block.Directions;
import com.retiredroca.redstonepcbs.chip.Cell;
import com.retiredroca.redstonepcbs.chip.Part;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ComparatorMode;
import net.minecraft.world.level.block.state.properties.RedstoneSide;

import java.util.HashMap;
import java.util.Map;

/** Maps a chip cell to the vanilla block state it is rendered as in the 3D view (cached). */
public final class PcbBlockStates {
    private static final Map<Long, BlockState> CACHE = new HashMap<>();

    private PcbBlockStates() {}

    public static BlockState stateFor(Cell cell) {
        long key = (cell.part.ordinal() & 0xF)
                | ((long) (cell.facing.ordinal() & 0x7) << 4)
                | ((long) (cell.power & 0xF) << 7)
                | ((cell.powered ? 1L : 0L) << 11)
                | ((cell.subtract ? 1L : 0L) << 12)
                | ((cell.on ? 1L : 0L) << 13)
                | ((long) (cell.dustMask & 0xF) << 14)
                | ((long) (cell.delay & 0x7) << 18);
        return CACHE.computeIfAbsent(key, k -> build(cell));
    }

    private static BlockState build(Cell cell) {
        // Defensive: never build an invalid state, whatever the cell holds.
        com.retiredroca.redstonepcbs.chip.Dir facing = cell.part.sanitizeFacing(cell.facing);
        return switch (cell.part) {
            case SOLID -> Blocks.STONE.defaultBlockState();
            case GLASS -> Blocks.GLASS.defaultBlockState();
            case REDSTONE_BLOCK -> Blocks.REDSTONE_BLOCK.defaultBlockState();
            case NOTE_BLOCK -> Blocks.NOTE_BLOCK.defaultBlockState();
            case LAMP -> Blocks.REDSTONE_LAMP.defaultBlockState()
                    .setValue(RedstoneLampBlock.LIT, cell.powered);
            case DUST -> Blocks.REDSTONE_WIRE.defaultBlockState()
                    .setValue(RedStoneWireBlock.POWER, Math.max(0, Math.min(15, cell.power)))
                    .setValue(RedStoneWireBlock.NORTH, side(cell.dustMask, 1))
                    .setValue(RedStoneWireBlock.EAST, side(cell.dustMask, 2))
                    .setValue(RedStoneWireBlock.SOUTH, side(cell.dustMask, 4))
                    .setValue(RedStoneWireBlock.WEST, side(cell.dustMask, 8));
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
                    .setValue(BlockStateProperties.POWERED, cell.on);
            case BUTTON -> Blocks.STONE_BUTTON.defaultBlockState()
                    .setValue(BlockStateProperties.POWERED, cell.on);
            default -> Blocks.STONE.defaultBlockState();
        };
    }

    private static BlockState torchState(com.retiredroca.redstonepcbs.chip.Dir facing, boolean powered) {
        if (facing == com.retiredroca.redstonepcbs.chip.Dir.DOWN
                || facing == com.retiredroca.redstonepcbs.chip.Dir.UP) {
            return Blocks.REDSTONE_TORCH.defaultBlockState()
                    .setValue(RedstoneTorchBlock.LIT, powered);
        }
        // WallTorchBlock.FACING points away from the wall (opposite our attachment direction).
        return Blocks.REDSTONE_WALL_TORCH.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING,
                        Directions.toMinecraft(facing.opposite()))
                .setValue(RedstoneTorchBlock.LIT, powered);
    }

    private static RedstoneSide side(int mask, int bit) {
        return (mask & bit) != 0 ? RedstoneSide.SIDE : RedstoneSide.NONE;
    }
}
