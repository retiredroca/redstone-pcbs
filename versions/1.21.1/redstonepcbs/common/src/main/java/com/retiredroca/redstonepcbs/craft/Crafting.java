package com.retiredroca.redstonepcbs.craft;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Palette availability: a part can be placed when the player is in creative mode or holds the
 * matching item anywhere in their inventory. Placing consumes one of that item; removing refunds it.
 */
public final class Crafting {
    private Crafting() {}

    public static boolean available(Player player, Item item) {
        return player.isCreative() || count(player, item) > 0;
    }

    /** Consumes the item, or nothing in creative mode. */
    public static boolean consume(Player player, Item item) {
        return player.isCreative() || consumeOne(player, item);
    }

    public static int count(Player player, Item item) {
        int count = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (stack.is(item)) {
                count += stack.getCount();
            }
        }
        for (ItemStack stack : player.getInventory().offhand) {
            if (stack.is(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static boolean consumeOne(Player player, Item item) {
        for (ItemStack stack : player.getInventory().items) {
            if (stack.is(item)) {
                stack.shrink(1);
                return true;
            }
        }
        for (ItemStack stack : player.getInventory().offhand) {
            if (stack.is(item)) {
                stack.shrink(1);
                return true;
            }
        }
        return false;
    }
}
