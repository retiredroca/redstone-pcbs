package com.retiredroca.redstonepcbs.block;

import com.retiredroca.redstonepcbs.RedstonePcbs;
import com.retiredroca.redstonepcbs.chip.Dir;
import com.retiredroca.redstonepcbs.chip.PortCodec;
import com.retiredroca.redstonepcbs.chip.PortLink;
import com.retiredroca.redstonepcbs.net.S2COpenEditorPayload;
import com.retiredroca.redstonepcbs.net.S2CSnapshotPayload;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.Hopper;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A board. Its circuit lives in a {@value BoardSpace#SIZE}x{@value BoardSpace#SIZE}x{@value BoardSpace#SIZE}
 * region of the runtime board dimension as real blocks, so vanilla owns the redstone. This block
 * entity owns the region allocation, the client sync, the per-board flags, and the world item
 * gateway: each face exposes the inventories of the containers explicitly attached to it (see
 * {@link #attachFaces()}), so world hoppers can insert/extract through the PCB block. It also ticks
 * like a hopper, pulling from a container directly above it into the board's top face.
 */
public class PcbBlockEntity extends BlockEntity implements WorldlyContainer, Hopper {
    private static final int SIZE = BoardSpace.SIZE;
    /** Vanilla hopper cooldown between transfers, in game ticks. */
    private static final int MOVE_COOLDOWN = HopperBlockEntity.MOVE_ITEM_SPEED;

    private ChunkPos boardChunk;
    private boolean regionCleaned;
    private boolean initialized;
    private BlockState[] pendingGrid;
    /** The slots this board exposes to the world, rebuilt at most once per game tick. */
    private List<SlotRef> boundarySlots = List.of();
    private long boundaryBuiltAt = Long.MIN_VALUE;
    private boolean loggedMissingDimension;
    private final int[] lastFaceCount = {-1, -1, -1, -1, -1, -1};
    /** Hopper-style transfer cooldown, in game ticks. */
    private int cooldownTime;
    /** Gateway attachments: grid cell index -> the PCB face it is exposed on. */
    private final java.util.Map<Integer, Dir> attachFaces = new java.util.LinkedHashMap<>();
    /** Redstone ports: grid cell index -> the port bridging that cell to a PCB face. */
    private final java.util.Map<Integer, PortLink> ports = new java.util.LinkedHashMap<>();

    /**
     * The level each input port's cell was last notified about, so a face is only touched while it
     * carries a signal, and once more on the tick it drops to zero. See {@link #notifyPortCell}.
     */
    private final java.util.Map<Integer, Integer> notifiedLevels = new java.util.LinkedHashMap<>();

    /**
     * The level this board drives out of each world face, indexed by {@link Dir#ordinal()}. Written
     * once per server tick by {@link #publishPorts()} and read by {@link PcbBlock#getSignal}.
     */
    private final int[] outLevels = new int[Dir.VALUES.length];

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("redstonepcbs");

    public PcbBlockEntity(BlockPos pos, BlockState state) {
        super(RedstonePcbs.platform().pcbBlockEntityType(), pos, state);
    }

    /** The board dimension where the regions live, if the server is running. */
    public ServerLevel pcbLevel() {
        return level != null && level.getServer() != null
                ? com.retiredroca.redstonepcbs.dimension.RuntimeDimension.level(level.getServer())
                : null;
    }

    public BoardSpace space() {
        ServerLevel pcbLevel = pcbLevel();
        return pcbLevel != null && boardChunk != null
                ? new BoardSpace(pcbLevel, boardChunk, BoardChunks.baseY(pcbLevel))
                : null;
    }

    public boolean hasRegion() {
        return boardChunk != null;
    }

    /** Allocates the board region and primes the gateway now (called at placement). */
    public void ensureRegionNow() {
        if (level != null && !level.isClientSide) {
            ensureRegion();
            ensureBoundarySlots();
        }
    }

    private void ensureRegion() {
        if (level == null || level.isClientSide) {
            return;
        }
        ServerLevel pcbLevel = pcbLevel();
        if (pcbLevel == null) {
            if (!loggedMissingDimension) {
                loggedMissingDimension = true;
                LOGGER.warn("PCB {}: the board dimension is not available", worldPosition);
            }
            return;
        }
        BoardChunks chunks = BoardChunks.get(pcbLevel);
        if (boardChunk == null) {
            BoardChunks.Slot slot = chunks.allocate(pcbLevel);
            boardChunk = slot.chunk();
            LOGGER.info("PCB {}: allocated board region {} (base Y {})",
                    worldPosition, boardChunk, BoardChunks.baseY(pcbLevel));
            setChanged();
        }
        if (!regionCleaned) {
            regionCleaned = true;
            BoardSpace space = new BoardSpace(pcbLevel, boardChunk, BoardChunks.baseY(pcbLevel));
            // The board dimension is void, so a fresh board is already empty; nothing to wipe. The
            // grid stores arbitrary BlockStates now, so there is no foreign-block sweep either.
            // The board floor sits one layer below the grid (relativeY -1), so it neither consumes
            // a cell of the usable 16x16x16 volume nor is ever swept by clear (which only walks grid
            // cells). Idempotent, so re-ensuring on reload is harmless and old saves get one.
            space.ensureFloor();
            // Repair shapes in boards saved before this existed, and on any reload. A wire keeps a stale
            // connection when the block it was joined to was merely cleared, and because the grid is
            // snapshotted from the world and written back, that stale state survives a save/load cycle
            // untouched. Seeding every cell re-derives it through vanilla's own shape pass. Once per
            // region, not per tick: ensureRegion is called every tick, and the work is idempotent.
            int repaired = space.refreshAllShapes();
            if (repaired > 0) {
                LOGGER.info("PCB {}: re-derived {} stale block shape(s) on load", worldPosition, repaired);
            }
        }
        if (pendingGrid != null) {
            GridSerializer.apply(pendingGrid, new BoardSpace(pcbLevel, boardChunk, BoardChunks.baseY(pcbLevel)));
            pendingGrid = null;
        }
    }

    public void serverTick() {
        if (level == null || level.isClientSide) {
            return;
        }
        ensureRegion();
        ensureBoundarySlots();
        if (!ports.isEmpty()) {
            // Refresh the levels crossing the boundary. The bridge serves what is published here, so
            // without this a signal changing in the world would never reach the board.
            publishPorts();
        }
        if (!initialized) {
            initialized = true;
            // Old saves may carry locked neighbours from earlier builds; re-notify so adjacent
            // hoppers re-evaluate their lock state against this board's gateway.
            level.updateNeighborsAt(worldPosition, getBlockState().getBlock());
        }
        if (cooldownTime < MOVE_COOLDOWN) {
            cooldownTime++;
        } else {
            // Behaves like a hopper pulling from the block directly above, whatever it faces.
            if (HopperBlockEntity.suckInItems(level, this)) {
                cooldownTime = 0;
                setChanged();
                notifyGatewayContainers(Dir.UP);
            }
        }
    }

    // --- Hopper interface: lets vanilla's own suckInItems pull into this gateway -------------------

    @Override
    public double getLevelX() {
        return worldPosition.getX() + 0.5;
    }

    @Override
    public double getLevelY() {
        return worldPosition.getY() + 0.5;
    }

    @Override
    public double getLevelZ() {
        return worldPosition.getZ() + 0.5;
    }

    @Override
    public boolean isGridAligned() {
        return true;
    }

    // --- grid access ------------------------------------------------------------------------------

    public BlockState[] grid() {
        ensureRegion();
        BoardSpace space = space();
        return space != null ? GridSerializer.snapshot(space)
                : (pendingGrid != null ? pendingGrid : GridSerializer.emptyGrid());
    }

    public void setGrid(BlockState[] grid) {
        ensureRegion();
        BoardSpace space = space();
        if (space != null) {
            GridSerializer.apply(grid, space);
        } else {
            pendingGrid = grid;
        }
        setChanged();
    }

    public byte[] snapshotBytes() {
        return GridSerializer.write(grid());
    }

    // --- lifecycle --------------------------------------------------------------------------------

    public void sendEditorTo(ServerPlayer player, Dir face) {
        RedstonePcbs.platform().sendToPlayer(player, S2COpenEditorPayload.block(worldPosition, face.ordinal()));
        RedstonePcbs.platform().sendToPlayer(player,
                S2CSnapshotPayload.block(worldPosition, snapshotBytes(), attachBytes(), portBytes()));
    }

    public void onEdited() {
        setChanged();
    }

    /** Releases the board's region (called when the block is broken). */
    public void releaseRegion() {
        ServerLevel pcbLevel = pcbLevel();
        if (pcbLevel != null && boardChunk != null) {
            new BoardSpace(pcbLevel, boardChunk, BoardChunks.baseY(pcbLevel)).clear();
            BoardChunks.get(pcbLevel).free(pcbLevel, new BoardChunks.Slot(boardChunk));
        }
        boardChunk = null;
        regionCleaned = false;
        boundarySlots = List.of();
        // The region is gone, so its cell positions must stop serving levels.
        if (pcbLevel != null) {
            SignalBridge.publish(pcbLevel, java.util.Map.of());
        }
    }

    // --- world item gateway -----------------------------------------------------------------------

    /**
     * One exposed slot: the world face of the PCB it is exposed on, the in-board container, and the
     * container's own slot index.
     */
    private record SlotRef(Dir face, Container container, int slot) {}

    /** An in-board container and its editor-grid coords. */
    private record Cell(int x, int y, int z, Container container) {}

    /** Rebuilds the gateway slots at most once per game tick, independent of the block ticker. */
    private void ensureBoundarySlots() {
        ensureRegion();
        if (level == null) {
            boundarySlots = List.of();
            return;
        }
        long now = level.getGameTime();
        if (now != boundaryBuiltAt) {
            boundaryBuiltAt = now;
            rebuildBoundarySlots();
        }
    }

    private void notifyGatewayContainers(Dir face) {
        ensureBoundarySlots();
        Container notified = null;
        for (SlotRef ref : boundarySlots) {
            if (ref.face() == face && ref.container() != notified) {
                notified = ref.container();
                ref.container().setChanged();
            }
        }
    }

    /**
     * Rebuilds the gateway ports. Each PCB face exposes exactly the containers the editor assigned to
     * it, so a container is reachable from the world only once it has been attached with {@code G}.
     * Slots come from the container's own vanilla face rules where it has them, so an in-board
     * furnace or brewing stand keeps its real orientation.
     *
     * <p>Transfer direction by face:
     * <ul>
     *   <li>TOP: the block above may be air or a container (hopper, chest, furnace, blast furnace,
     *       smoker, brewer); the board pulls down from it.</li>
     *   <li>BOTTOM and SIDES: receive only, and only from a hopper directly attached to that face.</li>
     * </ul>
     * A directly attached hopper is itself the non-air block on the queried face, so there is no
     * air requirement on any face.
     */
    private void rebuildBoundarySlots() {
        BoardSpace space = space();
        if (space == null) {
            boundarySlots = List.of();
            return;
        }
        LevelChunk chunk = space.levelChunk();
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        List<Cell> cells = new ArrayList<>();
        for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
            if (!(entry.getValue() instanceof Container container)) {
                continue;
            }
            BlockPos p = entry.getKey();
            int x = p.getX() - minX;
            int y = p.getY() - space.baseY();
            int z = p.getZ() - minZ;
            if (x < 0 || x >= SIZE || y < 0 || y >= SIZE || z < 0 || z >= SIZE) {
                continue;
            }
            cells.add(new Cell(x, y, z, container));
        }

        List<SlotRef> list = new ArrayList<>();
        for (Map.Entry<Integer, Dir> e : attachFaces.entrySet()) {
            int index = e.getKey();
            if (index < 0 || index >= GridSerializer.COUNT) {
                continue;
            }
            int cx = BoardSpace.xOf(index);
            int cy = BoardSpace.yOf(index);
            int cz = BoardSpace.zOf(index);
            Cell cell = null;
            for (Cell c : cells) {
                if (c.x() == cx && c.y() == cy && c.z() == cz) {
                    cell = c;
                    break;
                }
            }
            if (cell == null) {
                continue;
            }
            Direction direction = Directions.toMinecraft(e.getValue());
            for (int slot : slotsFor(direction, cell.container())) {
                list.add(new SlotRef(e.getValue(), cell.container(), slot));
            }
        }
        if (list.size() != boundarySlots.size()) {
            LOGGER.info("PCB gateway {}: {} port slot(s) from {} container(s)",
                    worldPosition, list.size(), cells.size());
        }
        boundarySlots = list;
    }

    /**
     * The slots of {@code container} reachable from the PCB face the world is contacting, whose world
     * direction is {@code direction}. A {@link WorldlyContainer} restricts them with its own vanilla
     * face rules; a plain container (a hopper) exposes every slot, matching vanilla hopper behaviour.
     * The world direction is used unchanged: a hopper under the PCB queries {@code DOWN} and the
     * in-board container is asked about its own {@code DOWN}, exactly as a real adjacent hopper would.
     */
    private static int[] slotsFor(Direction direction, Container container) {
        if (container instanceof WorldlyContainer worldly) {
            return worldly.getSlotsForFace(direction);
        }
        int size = container.getContainerSize();
        int[] all = new int[size];
        for (int i = 0; i < size; i++) {
            all[i] = i;
        }
        return all;
    }

    private SlotRef slot(int index) {
        ensureBoundarySlots();
        return index >= 0 && index < boundarySlots.size() ? boundarySlots.get(index) : null;
    }

    @Override
    public int getContainerSize() {
        ensureBoundarySlots();
        return boundarySlots.size();
    }

    @Override
    public boolean isEmpty() {
        ensureBoundarySlots();
        for (SlotRef ref : boundarySlots) {
            if (!ref.container().getItem(ref.slot()).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        SlotRef ref = slot(slot);
        return ref == null ? ItemStack.EMPTY : ref.container().getItem(ref.slot());
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        SlotRef ref = slot(slot);
        if (ref == null) {
            return ItemStack.EMPTY;
        }
        ItemStack result = ref.container().removeItem(ref.slot(), amount);
        ref.container().setChanged();
        return result;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        SlotRef ref = slot(slot);
        if (ref == null) {
            return ItemStack.EMPTY;
        }
        ItemStack result = ref.container().removeItemNoUpdate(ref.slot());
        ref.container().setChanged();
        return result;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        SlotRef ref = slot(slot);
        if (ref != null) {
            ref.container().setItem(ref.slot(), stack);
            // Only the empty-slot insert path reaches this method; a transfer that merges into a
            // non-empty slot grows the stack in place instead, and notifyGatewayContainers covers it.
            ref.container().setChanged();
        }
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        SlotRef ref = slot(slot);
        return ref != null && ref.container().canPlaceItem(ref.slot(), stack);
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public void clearContent() {
    }

    @Override
    public int[] getSlotsForFace(@Nullable Direction direction) {
        ensureBoundarySlots();
        // The hopper-style pull passes no side; it enters from above, so it may only use the
        // containers attached to the board's top face.
        Dir face = direction == null ? Dir.UP : Directions.toChip(direction);
        int[] out = new int[boundarySlots.size()];
        int count = 0;
        for (int i = 0; i < boundarySlots.size(); i++) {
            if (boundarySlots.get(i).face() == face) {
                out[count++] = i;
            }
        }
        if (lastFaceCount[face.ordinal()] != count) {
            lastFaceCount[face.ordinal()] = count;
            LOGGER.info("PCB {}: {} port slot(s) on {}", worldPosition, count, direction);
        }
        return java.util.Arrays.copyOf(out, count);
    }

    @Override
    public boolean canPlaceItemThroughFace(int slot, ItemStack stack, @Nullable Direction direction) {
        SlotRef ref = slot(slot);
        // A null direction is the hopper-style pull, which enters from above: top face only.
        Dir face = direction == null ? Dir.UP : Directions.toChip(direction);
        if (ref == null || ref.face() != face
                || !ref.container().canPlaceItem(ref.slot(), stack)) {
            return false;
        }
        return !(ref.container() instanceof WorldlyContainer worldly)
                || worldly.canPlaceItemThroughFace(ref.slot(), stack, direction);
    }

    @Override
    public boolean canTakeItemThroughFace(int slot, ItemStack stack, @Nullable Direction direction) {
        SlotRef ref = slot(slot);
        Dir face = direction == null ? Dir.UP : Directions.toChip(direction);
        if (ref == null || ref.face() != face) {
            return false;
        }
        return !(ref.container() instanceof WorldlyContainer worldly)
                || worldly.canTakeItemThroughFace(ref.slot(), stack, direction);
    }

    // --- gateway attachments ----------------------------------------------------------------------

    /** The face each attached cell is exposed on (cell index -> face). */
    public java.util.Map<Integer, Dir> attachFaces() {
        return java.util.Collections.unmodifiableMap(attachFaces);
    }

    /** The face cell {@code index} is attached to, or {@code null}. */
    @Nullable
    public Dir attachFace(int index) {
        return attachFaces.get(index);
    }

    /** The cell currently owning {@code face}, or -1. */
    public int cellForFace(Dir face) {
        for (Map.Entry<Integer, Dir> e : attachFaces.entrySet()) {
            if (e.getValue() == face) {
                return e.getKey();
            }
        }
        return -1;
    }

    /**
     * Attaches {@code index} to {@code face}, or clears it when {@code face} is null. A face can only
     * be held by one cell, so any previous owner is cleared first. The boundary ports are rebuilt next
     * tick and the client is sent a fresh snapshot by the caller.
     */
    public void setAttachFace(int index, @Nullable Dir face) {
        if (face == null) {
            attachFaces.remove(index);
        } else {
            int owner = cellForFace(face);
            if (owner >= 0 && owner != index) {
                attachFaces.remove(owner);
            }
            attachFaces.put(index, face);
        }
        setChanged();
    }

    /**
     * Replaces every attachment with {@code faces}, used when a portable board is placed from its item.
     * Out-of-range cell indices are dropped, and a face claimed by more than one cell is left with its
     * last claimant.
     */
    public void setAttachFaces(java.util.Map<Integer, Dir> faces) {
        attachFaces.clear();
        for (Map.Entry<Integer, Dir> e : faces.entrySet()) {
            int index = e.getKey();
            if (index < 0 || index >= GridSerializer.COUNT || e.getValue() == null) {
                continue;
            }
            attachFaces.put(index, e.getValue());
        }
        setChanged();
    }

    /** Drops any attachment for {@code index} (used when the cell is cleared or replaced). */
    public void clearAttachFace(int index) {
        if (attachFaces.remove(index) != null) {
            setChanged();
        }
    }

    /** The attachment bytes for the snapshot payload. */
    public byte[] attachBytes() {
        return PcbAttach.encode(attachFaces);
    }

    // --- redstone ports ---------------------------------------------------------------------------

    /** Every redstone port on this board (cell index -> port). Unlike a face, one is not exclusive. */
    public java.util.Map<Integer, PortLink> ports() {
        return java.util.Collections.unmodifiableMap(ports);
    }

    /** The port on {@code index}, or {@code null}. */
    @Nullable
    public PortLink port(int index) {
        return ports.get(index);
    }

    /** The outcome of a port assignment, so the client can warn before displacing another cell. */
    public enum PortResult {
        /** Assigned, or the cell's port was cleared. */
        OK,
        /** The face is already assigned to a different cell; nothing changed. */
        FACE_TAKEN
    }

    /** The cell currently holding {@code face} as a port, or -1. */
    public int cellForPortFace(Dir face) {
        for (Map.Entry<Integer, PortLink> e : ports.entrySet()) {
            if (e.getValue().face() == face) {
                return e.getKey();
            }
        }
        return -1;
    }

    /**
     * Sets or clears {@code index}'s port. One designated port per PCB face: assigning a face another
     * cell already holds is refused rather than silently displacing that cell, so the editor can warn
     * first. A cell may still hold ports on several different faces.
     */
    public PortResult setPort(int index, @Nullable PortLink port) {
        return setPort(index, port, false);
    }

    /**
     * As {@link #setPort(int, PortLink)}, but {@code takeOver} allows displacing the cell that already
     * holds the face. The editor only sets it after the player confirms.
     */
    public PortResult setPort(int index, @Nullable PortLink port, boolean takeOver) {
        if (port == null) {
            ports.remove(index);
            publishPorts();
            setChanged();
            return PortResult.OK;
        }
        int owner = cellForPortFace(port.face());
        if (owner >= 0 && owner != index) {
            if (!takeOver) {
                return PortResult.FACE_TAKEN;
            }
            ports.remove(owner);
        }
        ports.put(index, port);
        publishPorts();
        setChanged();
        return PortResult.OK;
    }

    /** Replaces every port with {@code replacements}, used when a board is placed from its item. */
    public void setPorts(java.util.Map<Integer, PortLink> replacements) {
        ports.clear();
        for (Map.Entry<Integer, PortLink> e : replacements.entrySet()) {
            int index = e.getKey();
            if (index < 0 || index >= GridSerializer.COUNT || e.getValue() == null) {
                continue;
            }
            ports.put(index, e.getValue());
        }
        publishPorts();
        setChanged();
    }

    /** Drops any port for {@code index} (used when the cell is cleared or replaced). */
    public void clearPort(int index) {
        if (ports.remove(index) != null) {
            publishPorts();
            setChanged();
        }
    }

    /** The port bytes for the snapshot payload. */
    public byte[] portBytes() {
        return PortCodec.encode(ports);
    }

    /**
     * Publishes the ports to the signal bridge, which serves them to vanilla's signal reads. The board
     * dimension is where those reads happen, and this block entity lives in the world, so the bridge
     * keeps its own server-scoped map keyed by level.
     */
    private void publishPorts() {
        ServerLevel board = pcbLevel();
        BoardSpace space = space();
        if (board == null || space == null || level == null) {
            // No region means no cell positions, so there is nothing to serve. Publishing an empty
            // map also drops any ports a previous region of this board registered.
            if (board != null) {
                SignalBridge.publish(board, java.util.Map.of());
            }
            java.util.Arrays.fill(outLevels, 0);
            notifiedLevels.clear();
            return;
        }
        java.util.Map<BlockPos, SignalBridge.Served> byPos = new java.util.LinkedHashMap<>();
        // Reset before re-sampling: a port removed since the last tick must not leave its level
        // latched on the face, or the board would keep driving a face nothing is attached to.
        java.util.Arrays.fill(outLevels, 0);
        for (Map.Entry<Integer, PortLink> e : ports.entrySet()) {
            PortLink port = e.getValue();
            int level = port.isInput() ? worldLevelOn(port.face()) : 0;
            byPos.put(space.pos(e.getKey()), new SignalBridge.Served(port, level));
            if (port.isInput()) {
                notifyPortCell(board, e.getKey(), space.pos(e.getKey()), level);
            } else {
                notifiedLevels.remove(e.getKey());
            }
            if (port.isOutput()) {
                sampleOutput(port, space.pos(e.getKey()));
            }
        }
        notifiedLevels.keySet().removeIf(index -> !ports.containsKey(index));
        SignalBridge.publish(board, byPos);
    }

    /**
     * Tells the cell a port sits on that its level may have changed, so vanilla redstone on that cell
     * re-reads it. Only for a face that is actually live.
     *
     * <p>Without this the bridge is invisible to the board. A redstone wire recomputes {@code POWER}
     * in exactly three places — {@code onPlace}, {@code onRemove} and {@code neighborChanged}
     * ({@code RedStoneWireBlock}:361, 374, 404) — and there is no per-tick recompute anywhere. The port
     * level changes in the registry without any block state changing, so a wire would compute its
     * power once, at whatever the source happened to be doing at that instant, and keep it forever.
     * This is the same reason vanilla relies on notifications rather than polling.
     *
     * <p>Scoped to the one face that has a signal: a level arriving on the east face must not touch the
     * north face's cell, whose wire is already unpowered and has nothing to do. The one tick after a
     * level falls to zero still fires, so the wire that was lit actually goes out — otherwise a wire
     * would latch on permanently once it had been powered.
     *
     * <p>A torch is deliberately excluded. It answers {@code neighborChanged} by scheduling a tick that
     * flips {@code LIT} ({@code RedstoneTorchBlock}:96-99), and that tick both burns the torch out when
     * its support is powered and counts the flip, scheduling a 160-tick burnt-out cooldown past 100
     * toggles in 60 ticks ({@code RedstoneTorchBlock}:82-88, 142). This notification is not a real block
     * change — nothing about the cell moved — so it must not drive a state change that can latch. A
     * torch still tracks its support, because vanilla re-evaluates it whenever the support genuinely
     * changes state; what is withheld is the fabricated notification. The cell's neighbours are updated
     * instead, so the circuit around a torch still re-reads the port.
     */
    private void notifyPortCell(ServerLevel board, int cell, BlockPos cellPos, int level) {
        int previous = notifiedLevels.getOrDefault(cell, 0);
        if (level <= 0 && previous <= 0) {
            return;
        }
        notifiedLevels.put(cell, level);
        BlockState state = board.getBlockState(cellPos);
        Block block = state.getBlock();
        if (block instanceof RedstoneTorchBlock) {
            board.updateNeighborsAt(cellPos, block);
            return;
        }
        board.neighborChanged(cellPos, block, cellPos.relative(Direction.UP));
    }

    /**
     * Samples the board's level for an output port and holds it as this board's level for the port's
     * world face, which {@link PcbBlock#getSignal} then serves.
     *
     * <p>The level is taken along the port's own side axis rather than along the face, which is the
     * exact mirror of the input path: an input serves the level arriving on the face onto the port's
     * side, and an output takes the level leaving on the port's side and presents it on the face. The
     * two therefore read the same pin of the same component, which is what makes a board reading its
     * own output a no-op instead of a feedback loop.
     *
     * <p>Direction convention, from vanilla's own {@code SignalGetter.getDirectSignalTo}, which asks
     * {@code getDirectSignal(pos.below(), DOWN)} for the signal arriving at {@code pos} from below: the
     * argument names the side the <em>source</em> is on, and the returned value is the emission in the
     * opposite direction. So emission toward {@code side} is {@code getSignal(cell, side.getOpposite())}.
     */
    private void sampleOutput(PortLink port, BlockPos cellPos) {
        Direction side = Directions.toMinecraft(port.side());
        int level = Math.clamp(pcbLevel().getSignal(cellPos, side.getOpposite()), 0, 15);
        // Indexed by Direction.ordinal() on both the write here and the read in outputLevel, never by
        // Dir.ordinal(). The two enums happen to be declared in the same order today, but they are
        // distinct types and nothing enforces that: indexing by one and reading with the other would
        // send the level out of the wrong face with no compile error and no failing test if either
        // order ever changed. Converting here means only one enum is ever used as an index.
        int face = Directions.toMinecraft(port.face()).ordinal();
        if (outLevels[face] != 0 && outLevels[face] != level) {
            // The editor only lets one port own a face, so this means two disagree. Take the lower
            // cell index deterministically rather than letting map order decide, and say so.
            LOGGER.warn("PCB {}: face {} has two output ports ({} and {}) at different levels; "
                            + "keeping the first by cell index",
                    worldPosition, port.face(), outLevels[face], level);
        }
        if (outLevels[face] == 0) {
            outLevels[face] = level;
        }
    }

    /**
     * The level this board drives out of the given world face, sampled once per server tick by
     * {@link #publishPorts()}. Vanilla reads this through {@link PcbBlock#getSignal}, so a value
     * cached here is at most one tick old, the same latency a comparator reading its container has.
     */
    public int outputLevel(Direction face) {
        return outLevels[face.ordinal()];
    }

    /**
     * The level arriving on {@code face} from the world, read the way vanilla reads a neighbour: the
     * block in that direction, asked what it emits back toward this PCB. A neighbouring PCB answers the
     * same query once it emits, which is what makes board to world to board work without a special case.
     *
     * <p>This is a plain signal read, so it costs the same as any other neighbour check and needs no
     * scheduled tick of its own.
     */
    private int worldLevelOn(Dir face) {
        Direction dir = Directions.toMinecraft(face);
        return level.getSignal(worldPosition.relative(dir), dir);
    }

    // --- persistence ------------------------------------------------------------------------------

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (boardChunk != null) {
            tag.putInt("chunkX", boardChunk.x);
            tag.putInt("chunkZ", boardChunk.z);
        }
        if (!attachFaces.isEmpty()) {
            tag.putByteArray("attach", attachBytes());
        }
        if (!ports.isEmpty()) {
            tag.putByteArray("ports", portBytes());
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        boardChunk = tag.contains("chunkX") ? new ChunkPos(tag.getInt("chunkX"), tag.getInt("chunkZ")) : null;
        attachFaces.clear();
        if (tag.contains("attach")) {
            attachFaces.putAll(PcbAttach.decode(tag.getByteArray("attach")));
        }
        ports.clear();
        if (tag.contains("ports")) {
            ports.putAll(PortCodec.decode(tag.getByteArray("ports")));
        }
        publishPorts();
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return new CompoundTag();
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
