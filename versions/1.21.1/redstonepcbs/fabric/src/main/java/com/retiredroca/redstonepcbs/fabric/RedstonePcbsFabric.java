package com.retiredroca.redstonepcbs.fabric;

import com.retiredroca.redstonepcbs.RedstonePcbs;
import com.retiredroca.redstonepcbs.config.PcbsConfig;
import com.retiredroca.redstonepcbs.data.ServerStart;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

public class RedstonePcbsFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        RedstonePcbs.setPlatform(new FabricPlatform());
        RedstonePcbs.platform().registerContent();
        RedstonePcbs.platform().registerNetwork();
        PcbsConfig.load();
        ServerLifecycleEvents.SERVER_STARTING.register(ServerStart::onServerStarting);
    }
}
