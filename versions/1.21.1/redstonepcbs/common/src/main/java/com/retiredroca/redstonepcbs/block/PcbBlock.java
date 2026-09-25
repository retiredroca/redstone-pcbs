package com.retiredroca.redstonepcbs.block;

import com.retiredroca.redstonepcbs.RedstonePcbs;
import com.retiredroca.redstonepcbs.chip.Dir;
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
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;

import org.jetbrains.annotations.Nullable;

/** PCB interface block: right-click opens the editor, and it owns a board region in the board dimension. */
public class PcbBlock extends Block implements EntityBlock {
    /** Mirrors a face output for observers; the board's redstone lives in its region. */
    public static final IntegerProperty POWER = BlockStateProperties.POWER;

    public PcbBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(POWER, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(POWER);
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
                    new ChipData(ChipData.pack(be.attachBytes(), be.snapshotBytes())));
            Block.popResource(level, pos, drop);
            be.releaseRegion();
        }
    }

    @Override
    public int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        // World-facing redstone is not wired yet; the board's circuit is self-contained.
        return 0;
    }

    @Override
    public int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return 0;
    }
}
