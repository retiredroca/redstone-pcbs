package com.retiredroca.redstonepcbs.net;

import com.retiredroca.redstonepcbs.RedstonePcbs;
import com.retiredroca.redstonepcbs.block.BoardEdit;
import com.retiredroca.redstonepcbs.block.BoardSpace;
import com.retiredroca.redstonepcbs.block.BoardStates;
import com.retiredroca.redstonepcbs.block.GridSerializer;
import com.retiredroca.redstonepcbs.block.PcbAttach;
import com.retiredroca.redstonepcbs.block.PcbBlockEntity;
import com.retiredroca.redstonepcbs.chip.Dir;
import com.retiredroca.redstonepcbs.chip.PortCodec;
import com.retiredroca.redstonepcbs.chip.PortFlow;
import com.retiredroca.redstonepcbs.chip.BoardPort;
import com.retiredroca.redstonepcbs.chip.Part;
import com.retiredroca.redstonepcbs.config.PcbsConfig;
import com.retiredroca.redstonepcbs.craft.Crafting;
import com.retiredroca.redstonepcbs.data.Blueprint;
import com.retiredroca.redstonepcbs.data.ChipData;
import com.retiredroca.redstonepcbs.data.LibraryData;
import com.retiredroca.redstonepcbs.item.PcbItem;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Server-side handling for editor edits and the saved-designs library. Edits read the board's region
 * grid, change it, write it back (so Minecraft re-runs the redstone), and reply with a snapshot.
 */
public final class ModNetwork {
    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("redstonepcbs");

    private static final double MAX_EDIT_DISTANCE_SQR = 64.0;

    private ModNetwork() {}

    public static void openItemEditor(ServerPlayer player, int slot) {
        ItemStack stack = player.getInventory().getItem(slot);
        if (!(stack.getItem() instanceof PcbItem)) {
            return;
        }
        RedstonePcbs.platform().sendToPlayer(player,
                new S2COpenEditorPayload(C2SEditPayload.KIND_ITEM, BlockPos.ZERO, slot, Dir.UP.ordinal()));
        RedstonePcbs.platform().sendToPlayer(player, S2CSnapshotPayload.item(slot,
                GridSerializer.write(readGrid(player, stack)), readFaces(stack)));
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
            case C2SEditPayload.ACTION_REQUEST -> {
                sendBlockSnapshot(player, payload.pos(), be);
                return;
            }
            case C2SEditPayload.ACTION_SET_FACE -> {
                setFace(be, payload);
                sendBlockSnapshot(player, payload.pos(), be);
                return;
            }
            case C2SEditPayload.ACTION_SET_PORT -> {
                PortFlow flow = C2SEditPayload.unpackFlow(payload.packed());
                BoardPort wanted = flow == null ? null : new BoardPort(payload.index(), flow);
                if (!be.setPort(wanted)) {
                    // Only a cell outside the grid reaches here; the editor will not send one, so this
                    // is a stale client and the snapshot below drops what it just asked for.
                    LOGGER.info("PCB tap refused on cell {}: outside the grid", payload.index());
                }
                be.onEdited();
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
            // Placing, clearing or rotating a cell no longer matches its old gateway attachment.
            switch (payload.action()) {
                case C2SEditPayload.ACTION_SET, C2SEditPayload.ACTION_PLACE,
                        C2SEditPayload.ACTION_CLEAR, C2SEditPayload.ACTION_ROTATE -> {
                    be.clearAttachFace(payload.index());
                    if (be.port() != null && be.port().cell() == payload.index()) {
                        be.setPort(null);
                    }
                }
                default -> {
                }
            }
        }
        sendBlockSnapshot(player, payload.pos(), be);
    }

    private static void editItem(ServerPlayer player, C2SEditPayload payload) {
        ItemStack stack = player.getInventory().getItem(payload.slot());
        if (!(stack.getItem() instanceof PcbItem)) {
            return;
        }
        if (payload.action() == C2SEditPayload.ACTION_REQUEST) {
            sendItemSnapshot(player, payload.slot(), readGrid(player, stack), readFaces(stack), readPorts(stack));
            return;
        }
        BlockState[] grid = readGrid(player, stack);
        Map<Integer, Dir> faces = new LinkedHashMap<>(PcbAttach.decode(readFaces(stack)));
        BoardPort ports = PortCodec.decode(readPorts(stack));
        if (payload.action() == C2SEditPayload.ACTION_SET_FACE) {
            applyFace(faces, payload);
        } else if (payload.action() == C2SEditPayload.ACTION_SET_PORT) {
            ports = applyPort(payload);
        } else if (apply(player, grid, payload)) {
            // Placing, clearing or rotating a cell no longer matches its old gateway attachment.
            switch (payload.action()) {
                case C2SEditPayload.ACTION_SET, C2SEditPayload.ACTION_PLACE,
                        C2SEditPayload.ACTION_CLEAR, C2SEditPayload.ACTION_ROTATE -> {
                    faces.remove(payload.index());
                    if (ports != null && ports.cell() == payload.index()) {
                        ports = null;
                    }
                }
                default -> {
                }
            }
        }
        stack.set(RedstonePcbs.platform().chip(),
                new ChipData(ChipData.pack(PcbAttach.encode(faces), PortCodec.encode(ports),
                        GridSerializer.write(grid))));
        player.getInventory().setChanged();
        player.inventoryMenu.broadcastChanges();
        sendItemSnapshot(player, payload.slot(), grid, PcbAttach.encode(faces), PortCodec.encode(ports));
    }

    /** The redstone port bytes carried by a PCB item (empty when none). */
    public static byte[] readPorts(ItemStack stack) {
        ChipData data = stack.get(RedstonePcbs.platform().chip());
        return data == null ? PortCodec.EMPTY : data.ports();
    }

    /** The gateway attachment bytes carried by a PCB item (empty when none). */
    public static byte[] readFaces(ItemStack stack) {
        ChipData data = stack.get(RedstonePcbs.platform().chip());
        return data == null ? PcbAttach.EMPTY : data.faces();
    }

    private static void openUi(ServerPlayer player, PcbBlockEntity be, int index) {
        BoardSpace space = be.space();
        if (space == null) {
            return;
        }
        BlockEntity target = space.blockEntity(index);
        if (target != null && com.retiredroca.redstonepcbs.block.BoardMenus.open(player, target)) {
            return;
        }
        // No container menu here, so the right-click was aimed at a redstone part: apply the same
        // vanilla parity an ACTION_INTERACT would. BoardEdit.interact is a no-op for anything else.
        BlockState[] grid = be.grid();
        if (index >= 0 && index < grid.length) {
            grid[index] = BoardEdit.interact(grid[index]);
            be.setGrid(grid);
            be.onEdited();
        }
    }

    private static void sendBlockSnapshot(ServerPlayer player, BlockPos pos, PcbBlockEntity be) {
        RedstonePcbs.platform().sendToPlayer(player,
                S2CSnapshotPayload.block(pos, be.snapshotBytes(), be.attachBytes(), be.portBytes()));
    }

    private static void sendItemSnapshot(ServerPlayer player, int slot, BlockState[] grid) {
        sendItemSnapshot(player, slot, grid, PcbAttach.EMPTY);
    }

    private static void sendItemSnapshot(ServerPlayer player, int slot, BlockState[] grid, byte[] faces) {
        RedstonePcbs.platform().sendToPlayer(player,
                S2CSnapshotPayload.item(slot, GridSerializer.write(grid), faces));
    }

    private static void sendItemSnapshot(ServerPlayer player, int slot, BlockState[] grid, byte[] faces,
            byte[] ports) {
        RedstonePcbs.platform().sendToPlayer(player,
                S2CSnapshotPayload.item(slot, GridSerializer.write(grid), faces, ports));
    }

    /**
     * Assigns or clears the gateway face of a placed board's container at {@code index}. A face can
     * only be held by one cell, so {@link PcbBlockEntity#setAttachFace} clears any previous owner
     * before taking it, matching the item path.
     */
    private static void setFace(PcbBlockEntity be, C2SEditPayload payload) {
        int index = payload.index();
        if (index < 0 || index >= GridSerializer.COUNT) {
            return;
        }
        int packed = payload.packed() & 0xFF;
        if (packed == 0xFF) {
            be.setAttachFace(index, null);
            return;
        }
        if (packed >= Dir.VALUES.length) {
            return;
        }
        be.setAttachFace(index, Dir.byOrdinal(packed));
    }

    /**
     * Applies an {@code ACTION_SET_FACE} to a portable board's attachment map: a face belongs to one
     * cell, so any previous owner is cleared first; 0xFF clears the cell's face.
     */
    private static void applyFace(Map<Integer, Dir> faces, C2SEditPayload payload) {
        int index = payload.index();
        if (index < 0 || index >= GridSerializer.COUNT) {
            return;
        }
        int packed = payload.packed() & 0xFF;
        if (packed == 0xFF) {
            faces.remove(index);
            return;
        }
        if (packed >= Dir.VALUES.length) {
            return;
        }
        Dir face = Dir.byOrdinal(packed);
        faces.values().removeIf(f -> f == face);
        faces.put(index, face);
    }

    /**
     * Applies a bridge assignment to the item being edited. One bridge, so there is no conflict to
     * resolve: naming a different cell simply moves it.
     */
    private static BoardPort applyPort(C2SEditPayload payload) {
        int index = payload.index();
        if (index < 0 || index >= GridSerializer.COUNT) {
            return null;
        }
        PortFlow flow = C2SEditPayload.unpackFlow(payload.packed());
        return flow == null ? null : new BoardPort(index, flow);
    }

    // --- edit application -------------------------------------------------------------------------

    private static boolean apply(ServerPlayer player, BlockState[] grid, C2SEditPayload payload) {
        int index = payload.index();
        if (index < 0 || index >= GridSerializer.COUNT) {
            return false;
        }
        return switch (payload.action()) {
            case C2SEditPayload.ACTION_SET -> setCell(player, grid, index, payload);
            case C2SEditPayload.ACTION_PLACE -> placeItem(player, grid, index, payload);
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

    /**
     * Places an arbitrary item on the board. The item id is resolved to a state via
     * {@link BoardStates#forItem}; a known part consumes its own item, anything else consumes the
     * placed item. The replaced block is refunded by its known part's item (so redstone dust refunds
     * correctly) or by {@code Block.asItem()}.
     */
    private static boolean placeItem(ServerPlayer player, BlockState[] grid, int index, C2SEditPayload payload) {
        ResourceLocation id = ResourceLocation.tryParse(payload.text());
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
            return false;
        }
        Item item = BuiltInRegistries.ITEM.get(id);
        Dir facing = Dir.byOrdinal(payload.facing());
        BlockState state = BoardStates.forItem(item, facing);
        if (state == null || state.isAir()) {
            return false;
        }
        BlockState old = grid[index];
        if (old.equals(state)) {
            return false;
        }
        if (!player.isCreative() && !Crafting.consume(player, item)) {
            return false;
        }
        if (!old.isAir()) {
            refund(player, old);
        }
        grid[index] = state;
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
        byte[] grid = data == null ? new byte[0] : data.grid();
        return grid.length > 0
                ? GridSerializer.read(grid, player.level().holderLookup(Registries.BLOCK))
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
            case C2SLibraryPayload.ACTION_SHARE -> {
                shareDesign(player, payload);
                sendLibrary(player);
            }
            default -> sendLibrary(player);
        }
    }

    private static void sendLibrary(ServerPlayer player) {
        LibraryData data = LibraryData.get(player.serverLevel());
        List<LibraryData.Design> saved = data.designs(player.getUUID());
        List<S2CLibraryPayload.Design> designs = new ArrayList<>(saved.size());
        for (LibraryData.Design design : saved) {
            designs.add(new S2CLibraryPayload.Design(design.name(), design.data(), design.faces(),
                    design.author()));
        }
        int limit = PcbsConfig.maxDesigns();
        boolean canSave = saved.size() < limit
                && (player.isCreative() || Crafting.count(player, Items.PAPER) > 0);
        List<String> online = new ArrayList<>();
        for (ServerPlayer other : player.server.getPlayerList().getPlayers()) {
            if (!other.getUUID().equals(player.getUUID())) {
                online.add(other.getGameProfile().getName());
            }
        }
        RedstonePcbs.platform().sendToPlayer(player,
                new S2CLibraryPayload(designs, canSave, limit, PcbsConfig.allowImport(), online));
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
                GridSerializer.write(grid), targetFaces(player, payload), ""));
        data.setDirty();
        return true;
    }

    /**
     * Copies one of the sender's saved designs into another online player's library, stamped with the
     * sender's name. The target's own per-player limit still applies, and the copy is an ordinary
     * editable entry for them.
     */
    private static void shareDesign(ServerPlayer player, C2SLibraryPayload payload) {
        LibraryData data = LibraryData.get(player.serverLevel());
        List<LibraryData.Design> mine = data.designs(player.getUUID());
        if (payload.index() < 0 || payload.index() >= mine.size() || payload.name() == null) {
            return;
        }
        ServerPlayer target = null;
        for (ServerPlayer candidate : player.server.getPlayerList().getPlayers()) {
            if (!candidate.getUUID().equals(player.getUUID())
                    && candidate.getGameProfile().getName().equals(payload.name())) {
                target = candidate;
                break;
            }
        }
        if (target == null) {
            return;
        }
        List<LibraryData.Design> theirs = data.designs(target.getUUID());
        if (theirs.size() >= PcbsConfig.maxDesigns()) {
            return;
        }
        LibraryData.Design design = mine.get(payload.index());
        theirs.add(new LibraryData.Design(design.name(), design.data(), design.faces(),
                player.getGameProfile().getName()));
        data.setDirty();
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
        list.add(new LibraryData.Design(cleanName(payload.name(), "Imported " + (list.size() + 1)),
                bytes, payload.faces(), ""));
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
        LibraryData.Design saved = list.get(payload.index());
        BlockState[] design = GridSerializer.read(saved.data(),
                player.level().holderLookup(Registries.BLOCK));
        setTargetGrid(player, payload, design, saved.faces());
    }

    /** The gateway attachments of the board or item a library request targets. */
    private static byte[] targetFaces(ServerPlayer player, C2SLibraryPayload payload) {
        if (payload.kind() == C2SEditPayload.KIND_ITEM) {
            ItemStack stack = player.getInventory().getItem(payload.slot());
            return stack.getItem() instanceof PcbItem ? readFaces(stack) : PcbAttach.EMPTY;
        }
        return player.level().getBlockEntity(payload.pos()) instanceof PcbBlockEntity be
                ? be.attachBytes() : PcbAttach.EMPTY;
    }

    private static BlockState[] targetGrid(ServerPlayer player, C2SLibraryPayload payload) {
        if (payload.kind() == C2SEditPayload.KIND_ITEM) {
            ItemStack stack = player.getInventory().getItem(payload.slot());
            return stack.getItem() instanceof PcbItem ? readGrid(player, stack) : null;
        }
        return player.level().getBlockEntity(payload.pos()) instanceof PcbBlockEntity be ? be.grid() : null;
    }

    private static void setTargetGrid(ServerPlayer player, C2SLibraryPayload payload, BlockState[] grid,
            byte[] faces) {
        if (payload.kind() == C2SEditPayload.KIND_ITEM) {
            ItemStack stack = player.getInventory().getItem(payload.slot());
            if (stack.getItem() instanceof PcbItem) {
                stack.set(RedstonePcbs.platform().chip(),
                        new ChipData(ChipData.pack(faces, PortCodec.EMPTY, GridSerializer.write(grid))));
                player.getInventory().setChanged();
                player.inventoryMenu.broadcastChanges();
                sendItemSnapshot(player, payload.slot(), grid, faces, PortCodec.EMPTY);
            }
        } else if (player.level().getBlockEntity(payload.pos()) instanceof PcbBlockEntity be) {
            be.setGrid(grid);
            if (faces != null && faces.length > 0) {
                be.setAttachFaces(PcbAttach.decode(faces));
            }
            // A design carries no ports, so the target's old ones no longer match its new cells.
            be.setPort(null);
            be.onEdited();
            sendBlockSnapshot(player, payload.pos(), be);
        }
    }

    public static Item itemFor(Part part) {
        return BoardStates.itemFor(part);
    }
}
