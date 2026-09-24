package com.retiredroca.redstonepcbs.data;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/** Loader-neutral work run once when a server starts, from each loader's server-start hook. */
public final class ServerStart {
    private ServerStart() {}

    public static void onServerStarting(MinecraftServer server) {
        LevelDataScrub.scrub(server.getWorldPath(LevelResource.ROOT));
    }
}
