package com.retiredroca.redstonepcbs.client;

import com.retiredroca.redstonepcbs.net.ClientPacketSink;
import com.retiredroca.redstonepcbs.net.S2CLibraryPayload;
import com.retiredroca.redstonepcbs.net.S2COpenEditorPayload;
import com.retiredroca.redstonepcbs.net.S2CSnapshotPayload;

import net.minecraft.client.Minecraft;

/**
 * Client-only handling for editor packets. Installed lazily from loader client setup, so it is
 * never class-loaded on a dedicated server.
 */
public final class ClientEditorNetwork implements ClientPacketSink.Handler {
    private ClientEditorNetwork() {}

    public static void install() {
        ClientPacketSink.install(new ClientEditorNetwork());
    }

    @Override
    public void snapshot(S2CSnapshotPayload payload) {
        if (Minecraft.getInstance().screen instanceof PcbEditorScreen screen && screen.matches(payload)) {
            screen.acceptSnapshot(payload.data(), payload.faces(), payload.ports());
        }
    }

    @Override
    public void openEditor(S2COpenEditorPayload payload) {
        Minecraft.getInstance().setScreen(new PcbEditorScreen(payload.kind(), payload.pos(), payload.slot(),
                payload.face()));
    }

    @Override
    public void library(S2CLibraryPayload payload) {
        if (Minecraft.getInstance().screen instanceof PcbEditorScreen screen) {
            screen.acceptLibrary(payload);
        }
    }
}
