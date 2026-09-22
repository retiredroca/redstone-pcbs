package com.retiredroca.redstonepcbs.fabric;

import com.retiredroca.redstonepcbs.RedstonePcbs;

import net.fabricmc.api.ModInitializer;

public class RedstonePcbsFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        RedstonePcbs.setPlatform(new FabricPlatform());
        RedstonePcbs.platform().registerContent();
        RedstonePcbs.platform().registerNetwork();
    }
}
