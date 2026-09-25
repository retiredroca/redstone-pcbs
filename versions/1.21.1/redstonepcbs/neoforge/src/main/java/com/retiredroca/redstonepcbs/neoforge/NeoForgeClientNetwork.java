package com.retiredroca.redstonepcbs.neoforge;

import com.retiredroca.redstonepcbs.client.ClientEditorNetwork;
import com.retiredroca.redstonepcbs.client.EditorReturn;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;

/** NeoForge client setup: installs the editor packet sink and the return-to-editor screen hook. */
public final class NeoForgeClientNetwork {
    private NeoForgeClientNetwork() {}

    public static void init(IEventBus modBus) {
        ClientEditorNetwork.install();
        NeoForge.EVENT_BUS.addListener(NeoForgeClientNetwork::onScreenClosing);
        NeoForge.EVENT_BUS.addListener(NeoForgeClientNetwork::onClientTick);
    }

    private static void onScreenClosing(ScreenEvent.Closing event) {
        EditorReturn.onScreenRemoved(event.getScreen());
    }

    private static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        EditorReturn.clientTick();
    }
}
