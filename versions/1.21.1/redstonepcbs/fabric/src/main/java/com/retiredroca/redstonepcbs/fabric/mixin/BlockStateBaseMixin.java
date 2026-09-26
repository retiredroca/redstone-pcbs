package com.retiredroca.redstonepcbs.fabric.mixin;

import com.retiredroca.redstonepcbs.block.SignalBridge;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Intercepts every redstone level read so a board cell that is a signal port can report the bridged
 * level. The shared logic lives in {@link SignalBridge}; this class supplies only the injection point.
 *
 * <p>{@code BlockStateBase} is the single place every signal read passes through: it declares
 * {@code getSignal} and {@code getDirectSignal}, and nothing overrides them because {@code BlockState}
 * inherits both. Injecting here rather than into the {@code SignalGetter} interface avoids injecting
 * into an interface default method, and it runs before the block's own logic.
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockStateBaseMixin {

    @Inject(method = "getSignal", at = @At("HEAD"), cancellable = true)
    private void redstonepcbs$bridgeWeakSignal(BlockGetter level, BlockPos pos, Direction direction,
            CallbackInfoReturnable<Integer> cir) {
        Integer bridged = SignalBridge.levelAt(level, pos, direction);
        if (bridged != null) {
            cir.setReturnValue(bridged);
        }
    }

    @Inject(method = "getDirectSignal", at = @At("HEAD"), cancellable = true)
    private void redstonepcbs$bridgeDirectSignal(BlockGetter level, BlockPos pos, Direction direction,
            CallbackInfoReturnable<Integer> cir) {
        Integer bridged = SignalBridge.levelAt(level, pos, direction);
        if (bridged != null) {
            cir.setReturnValue(bridged);
        }
    }
}
