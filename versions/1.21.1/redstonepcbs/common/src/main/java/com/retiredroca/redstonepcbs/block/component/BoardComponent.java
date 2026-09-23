package com.retiredroca.redstonepcbs.block.component;

import com.retiredroca.redstonepcbs.block.level.BoardLevel;
import com.retiredroca.redstonepcbs.chip.Cell;
import com.retiredroca.redstonepcbs.chip.Part;

import net.minecraft.core.BlockPos;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlastFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.level.block.entity.CrafterBlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.SmokerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A container/processor part living inside the board. It owns a real vanilla block entity and runs
 * the vanilla tick against the virtual {@link BoardLevel}, so in-board furnaces, brewing stands,
 * crafters and hoppers behave exactly like their world counterparts. Menus are the vanilla ones
 * (the block entity is itself a {@link MenuProvider}).
 */
public abstract class BoardComponent {
    private final Part part;
    private final BlockEntity be;

    private BoardComponent(Part part, BlockEntity be) {
        this.part = part;
        this.be = be;
    }

    public Part part() {
        return part;
    }

    public BlockEntity blockEntity() {
        return be;
    }

    public MenuProvider menuProvider() {
        return be instanceof MenuProvider provider ? provider : null;
    }

    /** Runs one game tick of the part's vanilla logic. */
    public abstract void tick(BoardLevel level, int index, Cell cell);

    /** Refreshes the cell's comparator output from the part's inventory/progress. */
    protected final void refreshAnalog(BoardLevel level, int index) {
        BlockPos pos = level.vposOf(index);
        int analog = level.getBlockState(pos).getAnalogOutputSignal(level, pos);
        level.board().chip().setCellAnalog(index, analog);
    }

    public static BoardComponent create(Part part, BlockPos pos, BlockState state) {
        return switch (part) {
            case FURNACE -> new Furnace(part, new BoardFurnaceEntity(pos, state));
            case BLAST_FURNACE -> new Furnace(part, new BoardBlastFurnaceEntity(pos, state));
            case SMOKER -> new Furnace(part, new BoardSmokerEntity(pos, state));
            case BREWING_STAND -> new Brewing(part, new BoardBrewingEntity(pos, state));
            case CRAFTER -> new Crafter(part, new BoardCrafterEntity(pos, state));
            case HOPPER -> new Hopper(part, new BoardHopperEntity(pos, state));
            default -> null;
        };
    }

    // The board's virtual positions sit up to 15 blocks from the real board, so the vanilla
    // distance check in stillValid would immediately close any menu. These subclasses accept it.

    private static final class BoardFurnaceEntity extends FurnaceBlockEntity {
        BoardFurnaceEntity(BlockPos pos, BlockState state) {
            super(pos, state);
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }
    }

    private static final class BoardBlastFurnaceEntity extends BlastFurnaceBlockEntity {
        BoardBlastFurnaceEntity(BlockPos pos, BlockState state) {
            super(pos, state);
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }
    }

    private static final class BoardSmokerEntity extends SmokerBlockEntity {
        BoardSmokerEntity(BlockPos pos, BlockState state) {
            super(pos, state);
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }
    }

    private static final class BoardBrewingEntity extends BrewingStandBlockEntity {
        BoardBrewingEntity(BlockPos pos, BlockState state) {
            super(pos, state);
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }
    }

    private static final class BoardCrafterEntity extends CrafterBlockEntity {
        BoardCrafterEntity(BlockPos pos, BlockState state) {
            super(pos, state);
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }
    }

    private static final class BoardHopperEntity extends HopperBlockEntity {
        BoardHopperEntity(BlockPos pos, BlockState state) {
            super(pos, state);
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }
    }

    private static final class Furnace extends BoardComponent {
        Furnace(Part part, AbstractFurnaceBlockEntity be) {
            super(part, be);
        }

        @Override
        public void tick(BoardLevel level, int index, Cell cell) {
            BlockPos pos = level.vposOf(index);
            AbstractFurnaceBlockEntity.serverTick(level, pos, level.getBlockState(pos),
                    (AbstractFurnaceBlockEntity) blockEntity());
            refreshAnalog(level, index);
        }
    }

    private static final class Brewing extends BoardComponent {
        Brewing(Part part, BrewingStandBlockEntity be) {
            super(part, be);
        }

        @Override
        public void tick(BoardLevel level, int index, Cell cell) {
            BlockPos pos = level.vposOf(index);
            BrewingStandBlockEntity.serverTick(level, pos, level.getBlockState(pos),
                    (BrewingStandBlockEntity) blockEntity());
            refreshAnalog(level, index);
        }
    }

    private static final class Crafter extends BoardComponent {
        Crafter(Part part, CrafterBlockEntity be) {
            super(part, be);
        }

        @Override
        public void tick(BoardLevel level, int index, Cell cell) {
            BlockPos pos = level.vposOf(index);
            CrafterBlockEntity.serverTick(level, pos, level.getBlockState(pos),
                    (CrafterBlockEntity) blockEntity());
            refreshAnalog(level, index);
        }
    }

    private static final class Hopper extends BoardComponent {
        Hopper(Part part, HopperBlockEntity be) {
            super(part, be);
        }

        @Override
        public void tick(BoardLevel level, int index, Cell cell) {
            BlockPos pos = level.vposOf(index);
            HopperBlockEntity.pushItemsTick(level, pos, level.getBlockState(pos),
                    (HopperBlockEntity) blockEntity());
            refreshAnalog(level, index);
        }
    }
}
