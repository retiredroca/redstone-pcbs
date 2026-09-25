package com.retiredroca.redstonepcbs.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;

import java.util.Collection;
import java.util.List;

/**
 * Reads a vanilla creative tab's displayed items on the client, the same way
 * {@code CreativeModeInventoryScreen} does: rebuild the tab contents with the player's enabled feature
 * flags and permission level, then read {@code getDisplayItems()}.
 */
public final class CreativeTabItems {
    private CreativeTabItems() {}

    public static List<ItemStack> displayItems(ResourceKey<CreativeModeTab> key) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return List.of();
        }
        FeatureFlagSet features = mc.getConnection() != null
                ? mc.getConnection().enabledFeatures()
                : FeatureFlagSet.of();
        boolean op = mc.player != null && mc.player.canUseGameMasterBlocks();
        HolderLookup.Provider registries = mc.level.registryAccess();
        CreativeModeTabs.tryRebuildTabContents(features, op, registries);

        CreativeModeTab tab = mc.level.registryAccess()
                .lookupOrThrow(net.minecraft.core.registries.Registries.CREATIVE_MODE_TAB)
                .get(key)
                .map(holder -> holder.value())
                .orElse(null);
        if (tab == null) {
            return List.of();
        }
        Collection<ItemStack> items = tab.getDisplayItems();
        return List.copyOf(items);
    }
}
