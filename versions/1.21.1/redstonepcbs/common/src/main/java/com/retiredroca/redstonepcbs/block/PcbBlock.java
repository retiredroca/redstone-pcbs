package com.retiredroca.redstonepcbs.block;

import com.retiredroca.redstonepcbs.RedstonePcbs;
import com.retiredroca.redstonepcbs.chip.PortCodec;
import com.retiredroca.redstonepcbs.data.ChipData;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import org.jetbrains.annotations.Nullable;

/** PCB interface block: right-click opens the editor, and it owns a board region in the board dimension. */
public class PcbBlock extends Block implements EntityBlock {

    public PcbBlock(Properties properties) {
        super(properties);
    }

    public static Properties boardProperties() {
        return Properties.of()
                .mapColor(net.minecraft.world.level.material.MapColor.WOOD)
                .strength(1.0F, 6.0F)
                .sound(net.minecraft.world.level.block.SoundType.WOOD);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PcbBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
            BlockEntityType<T> type) {
        if (level.isClientSide) {
            return null;
        }
        return (lvl, pos, st, be) -> {
            if (be instanceof PcbBlockEntity pcb) {
                pcb.serverTick();
            }
        };
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hit) {
        openEditor(level, pos, player, hit.getDirection());
        return InteractionResult.SUCCESS;
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hit) {
        if (player.isSecondaryUseActive()) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        openEditor(level, pos, player, hit.getDirection());
        return ItemInteractionResult.SUCCESS;
    }

    private void openEditor(Level level, BlockPos pos, Player player, Direction face) {
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer
                && level.getBlockEntity(pos) instanceof PcbBlockEntity be) {
            be.sendEditorTo(serverPlayer, Directions.toChip(face));
        }
    }

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (!level.isClientSide) {
            // Let adjacent hoppers re-evaluate their lock state.
            level.updateNeighborsAt(pos, this);
        }
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof PcbBlockEntity be) {
            ChipData data = stack.get(RedstonePcbs.platform().chip());
            if (data != null) {
                byte[] grid = data.grid();
                if (grid.length > 0) {
                    be.setGrid(GridSerializer.read(grid, level.holderLookup(Registries.BLOCK)));
                }
                byte[] faces = data.faces();
                if (faces.length > 0) {
                    be.setAttachFaces(PcbAttach.decode(faces));
                }
                byte[] ports = data.ports();
                if (ports.length > 0) {
                    be.setPorts(PortCodec.decode(ports));
                }
            }
            be.ensureRegionNow();
        }
    }

    @Override
    public void playerDestroy(Level level, Player player, BlockPos pos, BlockState state,
            @Nullable BlockEntity blockEntity, ItemStack tool) {
        if (!level.isClientSide && blockEntity instanceof PcbBlockEntity be) {
            ItemStack drop = new ItemStack(RedstonePcbs.platform().pcbItem());
            drop.set(RedstonePcbs.platform().chip(),
                    new ChipData(ChipData.pack(be.attachBytes(), be.portBytes(), be.snapshotBytes())));
            Block.popResource(level, pos, drop);
            be.releaseRegion();
        }
    }

    /**
     * The level this board drives out of the given world face, or 0 where no output port owns it.
     *
     * <p>Direction convention: vanilla's {@code SignalGetter.getDirectSignalTo} asks
     * {@code getDirectSignal(pos.below(), DOWN)} for the signal arriving at {@code pos} from below, so
     * the argument names the side the <em>source</em> is on and the returned value is the emission in
     * the opposite direction. A reader sitting on face {@code direction} therefore asks with that same
     * {@code direction}, which is what receives the port's level here.
     *
     * <p>The value is sampled once per server tick by the block entity, so it is at most one tick stale
     * — the same latency a vanilla comparator reading its container has.
     */
    @Override
    public int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        if (level.getBlockEntity(pos) instanceof PcbBlockEntity be) {
            return be.outputLevel(direction);
        }
        return 0;
    }

    /**
     * Serves the same level as {@link #getSignal}, so a diode reading this board sees the same number
     * as a wire or comparator reading it. The board's own circuit is unaffected: this block is in the
     * world, not in the board dimension, so nothing inside the board can observe it.
     */
    @Override
    public int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return getSignal(state, level, pos, direction);
    }

    /**
     * Reports this block as a signal source so vanilla actually consults the two methods above.
     *
     * <p>This is load-bearing rather than decorative: {@code SignalGetter.getControlInputSignal}
     * short-circuits to {@code getDirectSignal} only when {@code isSignalSource()} is true
     * ({@code SignalGetter.java:80}), and a diode takes its control input from that path, so without
     * this a repeater or comparator beside a board would read 0 no matter what the port held.
     *
     * <p>Returning true unconditionally does not make an unported board emit: every one of vanilla's
     * four consumers ({@code SignalGetter.java:80}, {@code NaturalSpawner}, {@code RailBlock}, and
     * {@code RedStoneWireBlock}'s observer case) gates its result on the level these methods return,
     * which is 0 while no output port owns a face. Verified in the 1.21.1 sources rather than assumed.
     */
    @Override
    public boolean isSignalSource(BlockState state) {
        return true;
    }
}
