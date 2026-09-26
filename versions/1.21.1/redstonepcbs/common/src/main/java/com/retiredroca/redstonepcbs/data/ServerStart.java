package com.retiredroca.redstonepcbs.data;

import com.retiredroca.redstonepcbs.block.SignalBridge;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/** Loader-neutral work run once when a server starts, from each loader's server-start hook. */
public final class ServerStart {
    private ServerStart() {}

    public static void onServerStarting(MinecraftServer server) {
        LevelDataScrub.scrub(server.getWorldPath(LevelResource.ROOT));
        // The redstone bridge keys its ports by level instance, so a stopped server's level would
        // otherwise stay reachable from the static map. Clearing on start rather than on stop bounds
        // that to at most one dead level per session, and needs no loader-specific stop event.
        SignalBridge.clear();
    }
}
