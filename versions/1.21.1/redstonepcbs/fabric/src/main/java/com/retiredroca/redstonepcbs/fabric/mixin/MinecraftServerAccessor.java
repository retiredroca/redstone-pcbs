package com.retiredroca.redstonepcbs.fabric.mixin;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.progress.ChunkProgressListenerFactory;
import net.minecraft.world.level.storage.LevelStorageSource;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.concurrent.Executor;

/** Exposes the server internals needed to construct a {@code ServerLevel} for the board dimension. */
@Mixin(MinecraftServer.class)
public interface MinecraftServerAccessor {
    @Accessor("storageSource")
    LevelStorageSource.LevelStorageAccess redstonepcbs$getStorageSource();

    @Accessor("executor")
    Executor redstonepcbs$getExecutor();

    @Accessor("progressListenerFactory")
    ChunkProgressListenerFactory redstonepcbs$getProgressListenerFactory();
}
