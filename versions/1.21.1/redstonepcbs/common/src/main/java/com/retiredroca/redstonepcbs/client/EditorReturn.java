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

    /**
     * Restores a stashed editor once the world is showing again. The screen-removal hook only fires
     * on the loader's own event, and a container menu can be dismissed by paths that never reach it
     * (the player returning to the world, another screen replacing the menu). Polling here makes the
     * return-to-editor behaviour independent of how the menu was closed; the {@code pending == null}
     * guard keeps this from fighting the hook when it does fire.
     */
    public static void clientTick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (pending != null && minecraft.screen == null) {
            PcbEditorScreen editor = pending;
            pending = null;
            minecraft.setScreen(editor);
        }
    }
}
