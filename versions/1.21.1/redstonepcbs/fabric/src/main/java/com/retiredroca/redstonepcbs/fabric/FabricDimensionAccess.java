package com.retiredroca.redstonepcbs.fabric;

import com.retiredroca.redstonepcbs.dimension.RuntimeDimension;
import com.retiredroca.redstonepcbs.fabric.mixin.MappedRegistryAccessor;
import com.retiredroca.redstonepcbs.fabric.mixin.MinecraftServerAccessor;

import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
import net.minecraft.core.WritableRegistry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.WorldData;

import java.util.List;

/** Fabric-side implementation of the two operations that need direct access to server internals. */
public final class FabricDimensionAccess implements RuntimeDimension.Access {
    public static final FabricDimensionAccess INSTANCE = new FabricDimensionAccess();

    private FabricDimensionAccess() {}

    @Override
    public <T> Holder.Reference<T> register(Registry<T> registry, ResourceKey<T> key, T value) {
        MappedRegistry<T> mapped = (MappedRegistry<T>) registry;
        MappedRegistryAccessor accessor = (MappedRegistryAccessor) mapped;
        boolean frozen = accessor.redstonepcbs$isFrozen();
        if (frozen) {
            accessor.redstonepcbs$setFrozen(false);
        }
        Holder.Reference<T> holder = ((WritableRegistry<T>) mapped).register(key, value, RegistrationInfo.BUILT_IN);
        if (frozen) {
            mapped.freeze();
        }
        return holder;
    }

    @Override
    public ServerLevel createAndRegisterLevel(MinecraftServer server, ResourceKey<Level> key, LevelStem stem) {
        MinecraftServerAccessor accessor = (MinecraftServerAccessor) server;
        WorldData worldData = server.getWorldData();
        DerivedLevelData data = new DerivedLevelData(worldData, worldData.overworldData());
        ServerLevel level = new ServerLevel(
                server,
                accessor.redstonepcbs$getExecutor(),
                accessor.redstonepcbs$getStorageSource(),
                data,
                key,
                stem,
                accessor.redstonepcbs$getProgressListenerFactory().create(10),
                worldData.isDebugWorld(),
                BiomeManager.obfuscateSeed(worldData.worldGenOptions().seed()),
                List.of(),
                false,
                null);
        ((com.retiredroca.redstonepcbs.fabric.DimensionLevelRegistrar) server).redstonepcbs$registerLevel(level);
        return level;
    }
}
