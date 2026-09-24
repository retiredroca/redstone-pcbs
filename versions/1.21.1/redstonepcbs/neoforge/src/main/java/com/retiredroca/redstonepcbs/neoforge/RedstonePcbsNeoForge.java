package com.retiredroca.redstonepcbs.neoforge;

import com.retiredroca.redstonepcbs.RedstonePcbs;
import com.retiredroca.redstonepcbs.config.PcbsConfig;
import com.retiredroca.redstonepcbs.data.ServerStart;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;

@Mod(RedstonePcbs.MOD_ID)
public class RedstonePcbsNeoForge {
    public RedstonePcbsNeoForge(IEventBus modBus) {
        RedstonePcbs.setPlatform(new NeoForgePlatform(modBus));
        RedstonePcbs.platform().registerContent();
        RedstonePcbs.platform().registerNetwork();
        PcbsConfig.load();
        NeoForge.EVENT_BUS.addListener(RedstonePcbsNeoForge::onServerStarting);
        if (FMLEnvironment.dist.isClient()) {
            NeoForgeClientNetwork.init(modBus);
        }
    }

    private static void onServerStarting(net.neoforged.neoforge.event.server.ServerStartingEvent event) {
        ServerStart.onServerStarting(event.getServer());
    }
}
