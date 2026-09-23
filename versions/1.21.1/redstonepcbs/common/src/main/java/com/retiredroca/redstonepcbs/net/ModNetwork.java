package com.retiredroca.redstonepcbs.net;

import com.retiredroca.redstonepcbs.RedstonePcbs;
import com.retiredroca.redstonepcbs.block.PcbBlockEntity;
import com.retiredroca.redstonepcbs.chip.ChipSerializer;
import com.retiredroca.redstonepcbs.chip.ChipWorld;
import com.retiredroca.redstonepcbs.chip.Dir;
import com.retiredroca.redstonepcbs.chip.Part;
import com.retiredroca.redstonepcbs.data.ChipData;
import com.retiredroca.redstonepcbs.data.LibraryData;
import com.retiredroca.redstonepcbs.item.PcbItem;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Server-side handling for editor edits and the saved-designs library. Kept loader-agnostic; each
 * loader's networking adapter routes received packets here and runs them on the server thread.
 */
public final class ModNetwork {
    private static final double MAX_EDIT_DISTANCE_SQR = 64.0;

    private ModNetwork() {}

    public static void openItemEditor(ServerPlayer player, int slot) {
        ItemStack stack = player.getInventory().getItem(slot);
        if (!(stack.getItem() instanceof PcbItem)) {
            return;
        }
        RedstonePcbs.platform().sendToPlayer(player,
                new S2COpenEditorPayload(C2SEditPayload.KIND_ITEM, BlockPos.ZERO, slot, Dir.UP.ordinal()));
        RedstonePcbs.platform().sendToPlayer(player,
                S2CSnapshotPayload.item(slot, ChipSerializer.write(readChip(stack))));
    }

    public static void handleEdit(ServerPlayer player, C2SEditPayload payload) {
        if (payload.kind() == C2SEditPayload.KIND_ITEM) {
            editItem(player, payload);
        } else {
            editBlock(player, payload);
        }
    }

    private static void editBlock(ServerPlayer player, C2SEditPayload payload) {
        if (!(player.level().getBlockEntity(payload.pos()) instanceof PcbBlockEntity be)) {
            return;
        }
        if (player.distanceToSqr(payload.pos().getX() + 0.5, payload.pos().getY() + 0.5,
                payload.pos().getZ() + 0.5) > MAX_EDIT_DISTANCE_SQR) {
            return;
        }
        ChipWorld chip = be.chip();
        if (payload.action() == C2SEditPayload.ACTION_SET_FILTER
                || payload.action() == C2SEditPayload.ACTION_CLEAR_FILTER) {
            Part filterPart = payload.action() == C2SEditPayload.ACTION_CLEAR_FILTER
                    ? Part.AIR : Part.byOrdinal(payload.part());
            Item filterItem = itemFor(filterPart);
            be.setFilter(filterItem == null ? ItemStack.EMPTY : new ItemStack(filterItem));
            be.onEdited();
            RedstonePcbs.platform().sendToPlayer(player,
                    S2CSnapshotPayload.block(payload.pos(), ChipSerializer.write(chip)));
            return;
        }
        if (payload.action() == C2SEditPayload.ACTION_OPEN_UI) {
            MenuProvider provider = be.componentMenu(payload.index());
            if (provider != null) {
                player.openMenu(provider);
            }
            return;
        }
        boolean changed = apply(player, chip, payload);
        if (changed) {
            be.onEdited();
        }
        RedstonePcbs.platform().sendToPlayer(player,
                S2CSnapshotPayload.block(payload.pos(), ChipSerializer.write(chip)));
    }

    private static void editItem(ServerPlayer player, C2SEditPayload payload) {
        ItemStack stack = player.getInventory().getItem(payload.slot());
        if (!(stack.getItem() instanceof PcbItem)) {
            return;
        }
        ChipWorld chip = readChip(stack);
        boolean changed = apply(player, chip, payload);
        if (changed) {
            stack.set(RedstonePcbs.platform().chip(), new ChipData(ChipSerializer.write(chip)));
            player.getInventory().setChanged();
            player.inventoryMenu.broadcastChanges();
        }
        RedstonePcbs.platform().sendToPlayer(player,
                S2CSnapshotPayload.item(payload.slot(), ChipSerializer.write(chip)));
    }

    // --- library ----------------------------------------------------------------------------------

    public static void handleLibrary(ServerPlayer player, C2SLibraryPayload payload) {
        switch (payload.action()) {
            case C2SLibraryPayload.ACTION_SAVE -> {
                if (saveDesign(player, payload)) {
                    sendLibrary(player);
                }
            }
            case C2SLibraryPayload.ACTION_DELETE -> {
                deleteDesign(player, payload.index());
                sendLibrary(player);
            }
            case C2SLibraryPayload.ACTION_APPLY -> {
                applyDesign(player, payload);
                sendLibrary(player);
            }
            default -> sendLibrary(player);
        }
    }

    private static void sendLibrary(ServerPlayer player) {
        List<LibraryData.Design> saved = LibraryData.get(player.serverLevel()).designs(player.getUUID());
        List<S2CLibraryPayload.Design> designs = new ArrayList<>(saved.size());
        for (LibraryData.Design design : saved) {
            designs.add(new S2CLibraryPayload.Design(design.name(), design.data()));
        }
        boolean canSave = player.isCreative() || countItem(player, Items.PAPER) > 0;
        RedstonePcbs.platform().sendToPlayer(player, new S2CLibraryPayload(designs, canSave));
    }

    private static boolean saveDesign(ServerPlayer player, C2SLibraryPayload payload) {
        ChipWorld chip = targetChip(player, payload);
        if (chip == null) {
            return false;
        }
        if (!player.isCreative() && !consumeItem(player, Items.PAPER, 1)) {
            return false;
        }
        LibraryData data = LibraryData.get(player.serverLevel());
        List<LibraryData.Design> list = data.designs(player.getUUID());
        list.add(new LibraryData.Design("Design " + (list.size() + 1), ChipSerializer.write(chip)));
        data.setDirty();
        return true;
    }

    private static void deleteDesign(ServerPlayer player, int index) {
        LibraryData data = LibraryData.get(player.serverLevel());
        List<LibraryData.Design> list = data.designs(player.getUUID());
        if (index >= 0 && index < list.size()) {
            list.remove(index);
            data.setDirty();
        }
    }

    private static void applyDesign(ServerPlayer player, C2SLibraryPayload payload) {
        LibraryData data = LibraryData.get(player.serverLevel());
        List<LibraryData.Design> list = data.designs(player.getUUID());
        if (payload.index() < 0 || payload.index() >= list.size()) {
            return;
        }
        ChipWorld current = targetChip(player, payload);
        if (current == null) {
            return;
        }
        ChipWorld design = ChipSerializer.read(list.get(payload.index()).data());
        if (!pay(player, design)) {
            return;
        }
        refundAll(player, current);
        setTargetChip(player, payload, design);
        syncTarget(player, payload);
    }

    private static ChipWorld targetChip(ServerPlayer player, C2SLibraryPayload payload) {
        if (payload.kind() == C2SEditPayload.KIND_ITEM) {
            ItemStack stack = player.getInventory().getItem(payload.slot());
            return stack.getItem() instanceof PcbItem ? readChip(stack) : null;
        }
        return player.level().getBlockEntity(payload.pos()) instanceof PcbBlockEntity be ? be.chip() : null;
    }

    private static void setTargetChip(ServerPlayer player, C2SLibraryPayload payload, ChipWorld chip) {
        if (payload.kind() == C2SEditPayload.KIND_ITEM) {
            ItemStack stack = player.getInventory().getItem(payload.slot());
            if (stack.getItem() instanceof PcbItem) {
                stack.set(RedstonePcbs.platform().chip(), new ChipData(ChipSerializer.write(chip)));
                player.getInventory().setChanged();
                player.inventoryMenu.broadcastChanges();
            }
        } else if (player.level().getBlockEntity(payload.pos()) instanceof PcbBlockEntity be) {
            be.setChip(chip);
            be.onEdited();
        }
    }

    private static void syncTarget(ServerPlayer player, C2SLibraryPayload payload) {
        if (payload.kind() == C2SEditPayload.KIND_ITEM) {
            ItemStack stack = player.getInventory().getItem(payload.slot());
            RedstonePcbs.platform().sendToPlayer(player,
                    S2CSnapshotPayload.item(payload.slot(), ChipSerializer.write(readChip(stack))));
        } else {
            RedstonePcbs.platform().sendToPlayer(player,
                    S2CSnapshotPayload.block(payload.pos(), ChipSerializer.write(
                            player.level().getBlockEntity(payload.pos()) instanceof PcbBlockEntity be
                                    ? be.chip() : new ChipWorld())));
        }
    }

    /** Checks the player can afford every part in {@code design}, then consumes the items. */
    private static boolean pay(ServerPlayer player, ChipWorld design) {
        if (player.isCreative()) {
            return true;
        }
        Map<Item, Integer> need = new LinkedHashMap<>();
        for (int i = 0; i < design.cellCount(); i++) {
            Part part = design.cell(i).part;
            if (part != Part.AIR) {
                Item item = itemFor(part);
                if (item != null) {
                    need.merge(item, 1, Integer::sum);
                }
            }
        }
        for (Map.Entry<Item, Integer> e : need.entrySet()) {
            if (countItem(player, e.getKey()) < e.getValue()) {
                return false;
            }
        }
        for (Map.Entry<Item, Integer> e : need.entrySet()) {
            consumeItem(player, e.getKey(), e.getValue());
        }
        return true;
    }

    // --- edit actions -----------------------------------------------------------------------------

    public static ChipWorld readChip(ItemStack stack) {
        ChipData data = stack.get(RedstonePcbs.platform().chip());
        return data != null && data.data().length > 0 ? ChipSerializer.read(data.data()) : new ChipWorld();
    }

    private static boolean apply(ServerPlayer player, ChipWorld chip, C2SEditPayload payload) {
        int index = payload.index();
        if (index < 0 || index >= chip.cellCount()) {
            return false;
        }
        return switch (payload.action()) {
            case C2SEditPayload.ACTION_SET -> setCell(player, chip, index, payload);
            case C2SEditPayload.ACTION_CLEAR -> clearCell(player, chip, index);
            case C2SEditPayload.ACTION_INTERACT -> {
                chip.interact(index);
                yield true;
            }
            case C2SEditPayload.ACTION_ROTATE -> {
                chip.rotate(index);
                yield true;
            }
            case C2SEditPayload.ACTION_CYCLE_DELAY -> {
                chip.cycleRepeaterDelay(index);
                yield true;
            }
            case C2SEditPayload.ACTION_TOGGLE_MODE -> {
                chip.toggleComparatorMode(index);
                yield true;
            }
            case C2SEditPayload.ACTION_TOGGLE_HOPPER_MODE -> {
                chip.toggleHopperMode();
                yield true;
            }
            case C2SEditPayload.ACTION_PULSE_LAYER -> {
                chip.pulseLayer(payload.index());
                yield true;
            }
            case C2SEditPayload.ACTION_CLEAR_ALL -> {
                refundAll(player, chip);
                chip.clearAll();
                yield true;
            }
            default -> false;
        };
    }

    private static boolean setCell(ServerPlayer player, ChipWorld chip, int index, C2SEditPayload payload) {
        Part part = Part.byOrdinal(payload.part());
        if (part == Part.AIR) {
            return clearCell(player, chip, index);
        }
        Part old = chip.cell(index).part;
        if (old == part) {
            return false;
        }
        Dir facing = Dir.byOrdinal(payload.facing());
        if (part.needsSupport()) {
            facing = supportedFacing(chip, index, facing);
            if (facing == null) {
                return false;
            }
        } else {
            facing = part.sanitizeFacing(facing);
        }
        if (!player.isCreative()) {
            Item item = itemFor(part);
            if (item == null || !com.retiredroca.redstonepcbs.craft.Crafting.consume(player, item)) {
                return false;
            }
        }
        if (old != Part.AIR) {
            refund(player, old);
        }
        chip.set(index, part, facing);
        chip.cell(index).subtract = (payload.flags() & C2SEditPayload.FLAG_SUBTRACT) != 0;
        return true;
    }

    private static Dir supportedFacing(ChipWorld chip, int index, Dir requested) {
        if (chip.hasSupport(index, requested)) {
            return requested;
        }
        for (Dir d : Part.SUPPORTED_ORDER) {
            if (chip.hasSupport(index, d)) {
                return d;
            }
        }
        return null;
    }

    private static boolean clearCell(ServerPlayer player, ChipWorld chip, int index) {
        Part old = chip.cell(index).part;
        if (old == Part.AIR) {
            return false;
        }
        chip.clear(chip.xOf(index), chip.yOf(index), chip.zOf(index));
        refund(player, old);
        return true;
    }

    private static void refundAll(ServerPlayer player, ChipWorld chip) {
        for (int i = 0; i < chip.cellCount(); i++) {
            Part part = chip.cell(i).part;
            if (part != Part.AIR && part != Part.SOLID) {
                refund(player, part);
            }
        }
    }

    private static void refund(ServerPlayer player, Part part) {
        if (player.isCreative()) {
            return;
        }
        Item item = itemFor(part);
        if (item != null) {
            give(player, new ItemStack(item));
        }
    }

    private static void give(ServerPlayer player, ItemStack stack) {
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
    }

    private static int countItem(ServerPlayer player, Item item) {
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

    private static boolean consumeItem(ServerPlayer player, Item item, int amount) {
        int remaining = amount;
        for (ItemStack stack : player.getInventory().items) {
            if (stack.is(item)) {
                int take = Math.min(remaining, stack.getCount());
                stack.shrink(take);
                remaining -= take;
                if (remaining == 0) {
                    return true;
                }
            }
        }
        for (ItemStack stack : player.getInventory().offhand) {
            if (stack.is(item)) {
                int take = Math.min(remaining, stack.getCount());
                stack.shrink(take);
                remaining -= take;
                if (remaining == 0) {
                    return true;
                }
            }
        }
        return remaining == 0;
    }

    public static Item itemFor(Part part) {
        return switch (part) {
            case DUST -> Items.REDSTONE;
            case TORCH -> Items.REDSTONE_TORCH;
            case REPEATER -> Items.REPEATER;
            case COMPARATOR -> Items.COMPARATOR;
            case REDSTONE_BLOCK -> Items.REDSTONE_BLOCK;
            case LEVER -> Items.LEVER;
            case BUTTON -> Items.STONE_BUTTON;
            case SOLID -> Items.STONE;
            case LAMP -> Items.REDSTONE_LAMP;
            case OBSERVER -> Items.OBSERVER;
            case NOTE_BLOCK -> Items.NOTE_BLOCK;
            case GLASS -> Items.GLASS;
            case HOPPER -> Items.HOPPER;
            case FURNACE -> Items.FURNACE;
            case BLAST_FURNACE -> Items.BLAST_FURNACE;
            case SMOKER -> Items.SMOKER;
            case BREWING_STAND -> Items.BREWING_STAND;
            case CRAFTER -> Items.CRAFTER;
            default -> null;
        };
    }
}
