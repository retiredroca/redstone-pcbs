package com.retiredroca.redstonepcbs.neoforge.mixin;

import com.retiredroca.redstonepcbs.dimension.RuntimeDimension;
import com.retiredroca.redstonepcbs.neoforge.DimensionLevelRegistrar;
import com.retiredroca.redstonepcbs.neoforge.NeoForgeDimensionAccess;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.world.level.Level;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/**
 * Creates the board dimension after all vanilla levels have been prepared. The runtime registration
 * lives in {@link RuntimeDimension}; this mixin supplies the injection point and the shadowed level
 * map the new level is registered into.
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin implements DimensionLevelRegistrar {
    @Shadow
    @Final
    private Map<ResourceKey<Level>, ServerLevel> levels;

    @Inject(method = "prepareLevels", at = @At("RETURN"))
    private void redstonepcbs$ensureBoardDimension(ChunkProgressListener listener, CallbackInfo ci) {
        RuntimeDimension.ensure((MinecraftServer) (Object) this, NeoForgeDimensionAccess.INSTANCE);
    }

    /** Registers a runtime-created level, mirroring vanilla's own level registration. */
    @Override
    public void redstonepcbs$registerLevel(ServerLevel level) {
        this.levels.put(level.dimension(), level);
        level.tick(() -> true);
    }
}
