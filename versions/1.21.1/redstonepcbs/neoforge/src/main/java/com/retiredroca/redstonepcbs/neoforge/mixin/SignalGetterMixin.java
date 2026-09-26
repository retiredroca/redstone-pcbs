package com.retiredroca.redstonepcbs.neoforge.mixin;

import com.retiredroca.redstonepcbs.block.SignalBridge;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.SignalGetter;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets an input port reach the block sitting on its own cell, not only the blocks around it.
 *
 * <p>{@link BlockStateBaseMixin} serves the port's level when something <em>reads the port cell</em>.
 * That is not enough on its own: vanilla redstone works out a block's power by reading its six
 * neighbours — {@code RedStoneWireBlock.calculateTargetStrength} calls
 * {@code getBestNeighborSignal}, which calls {@code getSignal(pos.relative(dir), dir)} — so a block on
 * the port cell never reads its own position. The port cell could therefore never become powered, and
 * a wire placed on an input port stayed dark however it was wired.
 *
 * <p>These three methods are where the reader's own position is available, which is exactly the
 * information the signal-read path lacks. Folding the port's level in at {@code RETURN} and taking the
 * max leaves vanilla's own answer intact and only ever adds power, so the cell behaves as though a
 * signal source stood beside it rather than replacing what the block already computed.
 *
 * <p>They are interface defaults and neither {@code Level} nor {@code ServerLevel} overrides any of
 * them, so the interface is the target. The shared decision lives in {@link SignalBridge}; this class
 * supplies only the injection points.
 */
@Mixin(SignalGetter.class)
public interface SignalGetterMixin {

    @Inject(method = "getBestNeighborSignal", at = @At("RETURN"), cancellable = true)
    private void redstonepcbs$bridgePortCellBestNeighbor(BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        Integer bridged = SignalBridge.levelForCell((BlockGetter) (Object) this, pos);
        if (bridged != null && bridged > cir.getReturnValue()) {
            cir.setReturnValue(bridged);
        }
    }

    @Inject(method = "hasNeighborSignal", at = @At("RETURN"), cancellable = true)
    private void redstonepcbs$bridgePortCellHasNeighbor(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        Integer bridged = SignalBridge.levelForCell((BlockGetter) (Object) this, pos);
        if (bridged != null && bridged > 0) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "getDirectSignalTo", at = @At("RETURN"), cancellable = true)
    private void redstonepcbs$bridgePortCellDirect(BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        Integer bridged = SignalBridge.levelForCell((BlockGetter) (Object) this, pos);
        if (bridged != null && bridged > cir.getReturnValue()) {
            cir.setReturnValue(bridged);
        }
    }
}
