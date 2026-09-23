package com.retiredroca.redstonepcbs.fabric;

import com.retiredroca.redstonepcbs.client.ClientEditorNetwork;
import com.retiredroca.redstonepcbs.client.EditorReturn;
import com.retiredroca.redstonepcbs.net.ClientPacketSink;
import com.retiredroca.redstonepcbs.net.S2CLibraryPayload;
import com.retiredroca.redstonepcbs.net.S2COpenEditorPayload;
import com.retiredroca.redstonepcbs.net.S2CSnapshotPayload;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;

/** Fabric client entrypoint: installs the editor packet handlers and the return-to-editor hook. */
public class RedstonePcbsFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientEditorNetwork.install();
        ClientPlayNetworking.registerGlobalReceiver(S2CSnapshotPayload.TYPE,
                (payload, context) -> context.client().execute(() -> ClientPacketSink.snapshot(payload)));
        ClientPlayNetworking.registerGlobalReceiver(S2COpenEditorPayload.TYPE,
                (payload, context) -> context.client().execute(() -> ClientPacketSink.openEditor(payload)));
        ClientPlayNetworking.registerGlobalReceiver(S2CLibraryPayload.TYPE,
                (payload, context) -> context.client().execute(() -> ClientPacketSink.library(payload)));

        ScreenEvents.AFTER_INIT.register((client, screen, width, height) ->
                ScreenEvents.remove(screen).register(EditorReturn::onScreenRemoved));
    }
}
