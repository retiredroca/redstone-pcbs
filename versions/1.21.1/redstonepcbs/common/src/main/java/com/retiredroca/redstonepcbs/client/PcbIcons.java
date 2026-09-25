package com.retiredroca.redstonepcbs.client;

import com.retiredroca.redstonepcbs.block.BoardStates;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * Palette metadata for the editor: the creative tabs we mirror and the item icons for the chip parts.
 * The palette itself is sourced from the live creative-tab contents (see {@link #entries}), so it
 * tracks what the game and other mods actually offer.
 */
public final class PcbIcons {
    /**
     * Palette categories, mirroring the creative inventory's tabs (narrowed to what the board needs:
     * no tools/combat/food/spawn eggs/operator tabs). {@link #SEARCH} is a query tab, not a category.
     */
    public enum Tab {
        SEARCH("Search", Items.COMPASS, null),
        BUILDING_BLOCKS("Building Blocks", Items.BRICKS, CreativeModeTabs.BUILDING_BLOCKS),
        REDSTONE("Redstone", Items.REDSTONE, CreativeModeTabs.REDSTONE_BLOCKS),
        FUNCTIONAL("Functional", Items.OAK_SIGN, CreativeModeTabs.FUNCTIONAL_BLOCKS),
        INGREDIENTS("Ingredients", Items.IRON_INGOT, CreativeModeTabs.INGREDIENTS);

        public final String label;
        public final Item icon;
        public final ResourceKey<CreativeModeTab> creativeTab;

        Tab(String label, Item icon, ResourceKey<CreativeModeTab> creativeTab) {
            this.label = label;
            this.icon = icon;
            this.creativeTab = creativeTab;
        }
    }

    public static final Tab[] TABS = Tab.values();

    /** One placeable palette entry: the item to place and its display stack. */
    public record Entry(Item item, ItemStack stack) {}

    /**
     * The items to show under {@code tab}: the creative tab's contents filtered to placeable items.
     * {@link Tab#SEARCH} aggregates every category tab.
     */
    public static List<Entry> entries(Tab tab) {
        List<Entry> out = new ArrayList<>();
        if (tab == Tab.SEARCH) {
            for (Tab other : TABS) {
                if (other != Tab.SEARCH) {
                    appendTab(out, other);
                }
            }
        } else {
            appendTab(out, tab);
        }
        return out;
    }

    private static void appendTab(List<Entry> out, Tab tab) {
        for (ItemStack stack : CreativeTabItems.displayItems(tab.creativeTab)) {
            if (stack.isEmpty()) {
                continue;
            }
            Item item = stack.getItem();
            if (!BoardStates.isPlaceable(item)) {
                continue;
            }
            if (out.stream().anyMatch(e -> e.item() == item)) {
                continue;
            }
            out.add(new Entry(item, stack));
        }
    }

    private PcbIcons() {}

    /** The registry id of an item, as sent to the server for a place action. */
    public static String idOf(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }
}
