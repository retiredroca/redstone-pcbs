package com.retiredroca.redstonepcbs.neoforge;

import com.retiredroca.redstonepcbs.RedstonePcbs;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;

@Mod(RedstonePcbs.MOD_ID)
public class RedstonePcbsNeoForge {
    public RedstonePcbsNeoForge(IEventBus modBus) {
        RedstonePcbs.setPlatform(new NeoForgePlatform(modBus));
        RedstonePcbs.platform().registerContent();
        RedstonePcbs.platform().registerNetwork();
        if (FMLEnvironment.dist.isClient()) {
            NeoForgeClientNetwork.init(modBus);
        }
    }
}
