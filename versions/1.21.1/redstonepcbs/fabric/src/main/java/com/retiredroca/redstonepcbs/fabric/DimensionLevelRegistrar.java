package com.retiredroca.redstonepcbs.fabric;

import net.minecraft.server.level.ServerLevel;

/**
 * Implemented by the {@code MinecraftServer} mixin so the runtime dimension can register its level.
 * Lives outside the mixin package: Mixin owns its configured package and forbids normal code from
 * referencing classes in it directly.
 */
public interface DimensionLevelRegistrar {
    void redstonepcbs$registerLevel(ServerLevel level);
}
