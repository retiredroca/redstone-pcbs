package com.retiredroca.redstonepcbs.neoforge;

import com.retiredroca.redstonepcbs.client.ClientEditorNetwork;

import net.neoforged.bus.api.IEventBus;

/** NeoForge client setup: installs the editor packet sink. Loaded only on the client. */
public final class NeoForgeClientNetwork {
    private NeoForgeClientNetwork() {}

    public static void init(IEventBus modBus) {
        ClientEditorNetwork.install();
    }
}
