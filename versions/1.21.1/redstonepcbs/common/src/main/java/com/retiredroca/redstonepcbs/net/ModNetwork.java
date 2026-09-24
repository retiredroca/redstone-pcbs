package com.retiredroca.redstonepcbs.net;

import com.retiredroca.redstonepcbs.RedstonePcbs;
import com.retiredroca.redstonepcbs.block.BoardEdit;
import com.retiredroca.redstonepcbs.block.BoardSpace;
import com.retiredroca.redstonepcbs.block.BoardStates;
import com.retiredroca.redstonepcbs.block.GridSerializer;
import com.retiredroca.redstonepcbs.block.PcbBlockEntity;
import com.retiredroca.redstonepcbs.chip.Dir;
import com.retiredroca.redstonepcbs.chip.Part;
import com.retiredroca.redstonepcbs.config.PcbsConfig;
import com.retiredroca.redstonepcbs.craft.Crafting;
import com.retiredroca.redstonepcbs.data.Blueprint;
import com.retiredroca.redstonepcbs.data.ChipData;
import com.retiredroca.redstonepcbs.data.LibraryData;
import com.retiredroca.redstonepcbs.item.PcbItem;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * Server-side handling for editor edits and the saved-designs library. Edits read the board's region
 * grid, change it, write it back (so Minecraft re-runs the redstone), and reply with a snapshot.
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
                S2CSnapshotPayload.item(slot, GridSerializer.write(readGrid(player, stack))));
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
        switch (payload.action()) {
            case C2SEditPayload.ACTION_OPEN_UI -> {
                openUi(player, be, payload.index());
                return;
            }
            case C2SEditPayload.ACTION_TOGGLE_INPUT -> {
                be.setExternalInput(!be.isExternalInput());
                return;
            }
            case C2SEditPayload.ACTION_TOGGLE_OUTPUT -> {
                be.setExternalOutput(!be.isExternalOutput());
                return;
            }
            case C2SEditPayload.ACTION_REQUEST -> {
                sendBlockSnapshot(player, payload.pos(), be);
                return;
            }
            default -> {
            }
        }
        BlockState[] grid = be.grid();
        if (apply(player, grid, payload)) {
            be.setGrid(grid);
            be.onEdited();
        }
        sendBlockSnapshot(player, payload.pos(), be);
    }

    private static void editItem(ServerPlayer player, C2SEditPayload payload) {
        ItemStack stack = player.getInventory().getItem(payload.slot());
        if (!(stack.getItem() instanceof PcbItem)) {
            return;
        }
        if (payload.action() == C2SEditPayload.ACTION_REQUEST) {
            sendItemSnapshot(player, payload.slot(), readGrid(player, stack));
            return;
        }
        BlockState[] grid = readGrid(player, stack);
        if (apply(player, grid, payload)) {
            stack.set(RedstonePcbs.platform().chip(), new ChipData(GridSerializer.write(grid)));
            player.getInventory().setChanged();
            player.inventoryMenu.broadcastChanges();
        }
        sendItemSnapshot(player, payload.slot(), grid);
    }

    private static void openUi(ServerPlayer player, PcbBlockEntity be, int index) {
        BoardSpace space = be.space();
        if (space == null) {
            return;
        }
        BlockEntity target = space.blockEntity(index);
        if (target != null) {
            com.retiredroca.redstonepcbs.block.BoardMenus.open(player, target);
        }
    }

    private static void sendBlockSnapshot(ServerPlayer player, BlockPos pos, PcbBlockEntity be) {
        RedstonePcbs.platform().sendToPlayer(player, S2CSnapshotPayload.block(pos, be.snapshotBytes()));
    }

    private static void sendItemSnapshot(ServerPlayer player, int slot, BlockState[] grid) {
        RedstonePcbs.platform().sendToPlayer(player,
                S2CSnapshotPayload.item(slot, GridSerializer.write(grid)));
    }

    // --- edit application -------------------------------------------------------------------------

    private static boolean apply(ServerPlayer player, BlockState[] grid, C2SEditPayload payload) {
        int index = payload.index();
        if (index < 0 || index >= GridSerializer.COUNT) {
            return false;
        }
        return switch (payload.action()) {
            case C2SEditPayload.ACTION_SET -> setCell(player, grid, index, payload);
            case C2SEditPayload.ACTION_CLEAR -> clearCell(player, grid, index);
            case C2SEditPayload.ACTION_ROTATE -> {
                grid[index] = BoardEdit.rotate(grid[index]);
                yield true;
            }
            case C2SEditPayload.ACTION_CYCLE_DELAY -> {
                grid[index] = BoardEdit.cycleDelay(grid[index]);
                yield true;
            }
            case C2SEditPayload.ACTION_TOGGLE_MODE -> {
                grid[index] = BoardEdit.toggleComparatorMode(grid[index]);
                yield true;
            }
            case C2SEditPayload.ACTION_INTERACT -> {
                grid[index] = BoardEdit.interact(grid[index]);
                yield true;
            }
            case C2SEditPayload.ACTION_PULSE_LAYER -> {
                pulseLayer(grid, index);
                yield true;
            }
            case C2SEditPayload.ACTION_CLEAR_ALL -> {
                clearAll(player, grid);
                yield true;
            }
            default -> false;
        };
    }

    private static boolean setCell(ServerPlayer player, BlockState[] grid, int index, C2SEditPayload payload) {
        Part part = Part.byOrdinal(payload.part());
        if (part == Part.AIR) {
            return clearCell(player, grid, index);
        }
        BlockState old = grid[index];
        if (old.getBlock() == BoardStates.initial(part, Dir.byOrdinal(payload.facing())).getBlock()) {
            return false;
        }
        if (!player.isCreative()) {
            Item item = itemFor(part);
            if (item == null || !Crafting.consume(player, item)) {
                return false;
            }
        }
        if (!old.isAir()) {
            refund(player, old);
        }
        grid[index] = BoardStates.initial(part, Dir.byOrdinal(payload.facing()));
        return true;
    }

    private static boolean clearCell(ServerPlayer player, BlockState[] grid, int index) {
        BlockState old = grid[index];
        if (old.isAir()) {
            return false;
        }
        refund(player, old);
        grid[index] = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        return true;
    }

    private static void clearAll(ServerPlayer player, BlockState[] grid) {
        for (int i = 0; i < grid.length; i++) {
            if (!grid[i].isAir()) {
                refund(player, grid[i]);
                grid[i] = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
            }
        }
    }

    private static void pulseLayer(BlockState[] grid, int layer) {
        if (layer < 0 || layer >= BoardSpace.SIZE) {
            return;
        }
        for (int x = 0; x < BoardSpace.SIZE; x++) {
            for (int z = 0; z < BoardSpace.SIZE; z++) {
                int index = BoardSpace.index(x, layer, z);
                grid[index] = BoardEdit.pulse(grid[index]);
            }
        }
    }

    private static void refund(ServerPlayer player, BlockState state) {
        if (player.isCreative()) {
            return;
        }
        Item item = state.getBlock().asItem();
        if (item != Items.AIR) {
            give(player, new ItemStack(item));
        }
    }

    private static void give(ServerPlayer player, ItemStack stack) {
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
    }

    public static BlockState[] readGrid(ServerPlayer player, ItemStack stack) {
        ChipData data = stack.get(RedstonePcbs.platform().chip());
        return data != null && data.data().length > 0
                ? GridSerializer.read(data.data(), player.level().holderLookup(Registries.BLOCK))
                : GridSerializer.emptyGrid();
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
            case C2SLibraryPayload.ACTION_IMPORT -> {
                if (importDesign(player, payload)) {
                    sendLibrary(player);
                }
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
        int limit = PcbsConfig.maxDesigns();
        boolean canSave = saved.size() < limit
                && (player.isCreative() || Crafting.count(player, Items.PAPER) > 0);
        RedstonePcbs.platform().sendToPlayer(player,
                new S2CLibraryPayload(designs, canSave, limit, PcbsConfig.allowImport()));
    }

    private static boolean saveDesign(ServerPlayer player, C2SLibraryPayload payload) {
        BlockState[] grid = targetGrid(player, payload);
        if (grid == null) {
            return false;
        }
        LibraryData data = LibraryData.get(player.serverLevel());
        List<LibraryData.Design> list = data.designs(player.getUUID());
        if (list.size() >= PcbsConfig.maxDesigns()) {
            return false;
        }
        if (!player.isCreative() && !Crafting.consume(player, Items.PAPER)) {
            return false;
        }
        list.add(new LibraryData.Design(cleanName(payload.name(), "Design " + (list.size() + 1)),
                GridSerializer.write(grid)));
        data.setDirty();
        return true;
    }

    /** Trims a player-supplied name to a printable, bounded label. */
    private static String cleanName(String raw, String fallback) {
        if (raw == null) {
            return fallback;
        }
        String trimmed = raw.strip();
        StringBuilder out = new StringBuilder(Math.min(trimmed.length(), LibraryData.MAX_NAME_LENGTH));
        for (int i = 0; i < trimmed.length() && out.length() < LibraryData.MAX_NAME_LENGTH; i++) {
            char c = trimmed.charAt(i);
            if (c >= ' ' && c != '\u00a7') {
                out.append(c);
            }
        }
        String name = out.toString().strip();
        return name.isEmpty() ? fallback : name;
    }

    /** Adds a shared blueprint to the player's library, validating the bytes first. */
    private static boolean importDesign(ServerPlayer player, C2SLibraryPayload payload) {
        if (!PcbsConfig.allowImport()) {
            return false;
        }
        byte[] bytes = payload.data();
        if (bytes == null || bytes.length == 0 || bytes.length > Blueprint.MAX_GRID_BYTES) {
            return false;
        }
        try {
            GridSerializer.read(bytes, player.level().holderLookup(Registries.BLOCK));
        } catch (RuntimeException e) {
            return false;
        }
        LibraryData data = LibraryData.get(player.serverLevel());
        List<LibraryData.Design> list = data.designs(player.getUUID());
        if (list.size() >= PcbsConfig.maxDesigns()) {
            return false;
        }
        list.add(new LibraryData.Design(cleanName(payload.name(), "Imported " + (list.size() + 1)), bytes));
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
        BlockState[] design = GridSerializer.read(list.get(payload.index()).data(),
                player.level().holderLookup(Registries.BLOCK));
        setTargetGrid(player, payload, design);
    }

    private static BlockState[] targetGrid(ServerPlayer player, C2SLibraryPayload payload) {
        if (payload.kind() == C2SEditPayload.KIND_ITEM) {
            ItemStack stack = player.getInventory().getItem(payload.slot());
            return stack.getItem() instanceof PcbItem ? readGrid(player, stack) : null;
        }
        return player.level().getBlockEntity(payload.pos()) instanceof PcbBlockEntity be ? be.grid() : null;
    }

    private static void setTargetGrid(ServerPlayer player, C2SLibraryPayload payload, BlockState[] grid) {
        if (payload.kind() == C2SEditPayload.KIND_ITEM) {
            ItemStack stack = player.getInventory().getItem(payload.slot());
            if (stack.getItem() instanceof PcbItem) {
                stack.set(RedstonePcbs.platform().chip(), new ChipData(GridSerializer.write(grid)));
                player.getInventory().setChanged();
                player.inventoryMenu.broadcastChanges();
                sendItemSnapshot(player, payload.slot(), grid);
            }
        } else if (player.level().getBlockEntity(payload.pos()) instanceof PcbBlockEntity be) {
            be.setGrid(grid);
            be.onEdited();
            sendBlockSnapshot(player, payload.pos(), be);
        }
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
