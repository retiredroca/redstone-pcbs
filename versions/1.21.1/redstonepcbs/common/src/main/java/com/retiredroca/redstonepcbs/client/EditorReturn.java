package com.retiredroca.redstonepcbs.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Reopens the PCB editor after a container/processor menu that was opened from it is closed, so the
 * player returns to editing rather than to the world.
 */
public final class EditorReturn {
    private static PcbEditorScreen pending;

    private EditorReturn() {}

    public static void stash(PcbEditorScreen screen) {
        pending = screen;
    }

    public static void onScreenRemoved(Screen removed) {
        if (pending == null || removed == pending) {
            return;
        }
        PcbEditorScreen editor = pending;
        pending = null;
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> minecraft.setScreen(editor));
    }
}
