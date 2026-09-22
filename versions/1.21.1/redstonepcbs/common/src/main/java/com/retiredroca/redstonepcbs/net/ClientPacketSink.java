package com.retiredroca.redstonepcbs.net;

/**
 * Server-safe indirection for client-bound packets. Payload handlers are registered on both sides
 * (NeoForge requires the type to exist server-side too), but the actual client work must not load
 * any client-only class on a dedicated server. The client entrypoint installs a handler; on the
 * server the calls are no-ops.
 */
public final class ClientPacketSink {
    public interface Handler {
        void snapshot(S2CSnapshotPayload payload);

        void openEditor(S2COpenEditorPayload payload);

        void library(S2CLibraryPayload payload);
    }

    private static Handler handler;

    private ClientPacketSink() {}

    public static void install(Handler newHandler) {
        handler = newHandler;
    }

    public static void snapshot(S2CSnapshotPayload payload) {
        if (handler != null) {
            handler.snapshot(payload);
        }
    }

    public static void openEditor(S2COpenEditorPayload payload) {
        if (handler != null) {
            handler.openEditor(payload);
        }
    }

    public static void library(S2CLibraryPayload payload) {
        if (handler != null) {
            handler.library(payload);
        }
    }
}
