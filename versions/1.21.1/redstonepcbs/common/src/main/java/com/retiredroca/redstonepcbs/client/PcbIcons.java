package com.retiredroca.redstonepcbs.client;

import com.retiredroca.redstonepcbs.chip.Part;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Vanilla item icons for the chip parts, shared by the palette and the editor grid. */
public final class PcbIcons {
    public static final Part[] PALETTE = {
            Part.SOLID, Part.DUST, Part.TORCH, Part.REPEATER, Part.COMPARATOR, Part.REDSTONE_BLOCK,
            Part.LAMP, Part.OBSERVER, Part.NOTE_BLOCK, Part.GLASS, Part.HOPPER
    };

    private PcbIcons() {}

    public static Item itemFor(Part part) {
        return switch (part) {
            case SOLID -> Items.STONE;
            case DUST -> Items.REDSTONE;
            case TORCH -> Items.REDSTONE_TORCH;
            case REPEATER -> Items.REPEATER;
            case COMPARATOR -> Items.COMPARATOR;
            case REDSTONE_BLOCK -> Items.REDSTONE_BLOCK;
            case LEVER -> Items.LEVER;
            case BUTTON -> Items.STONE_BUTTON;
            case LAMP -> Items.REDSTONE_LAMP;
            case OBSERVER -> Items.OBSERVER;
            case NOTE_BLOCK -> Items.NOTE_BLOCK;
            case GLASS -> Items.GLASS;
            case HOPPER -> Items.HOPPER;
            default -> Items.AIR;
        };
    }

    public static ItemStack stackFor(Part part) {
        Item item = itemFor(part);
        return item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
    }

    /** The vanilla display name, e.g. "Redstone Dust". */
    public static Component nameOf(Part part) {
        ItemStack stack = stackFor(part);
        return stack.isEmpty() ? Component.literal(part.name()) : stack.getHoverName();
    }
}
