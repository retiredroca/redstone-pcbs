package com.retiredroca.redstonepcbs.block;

import com.retiredroca.redstonepcbs.RedstonePcbs;
import com.retiredroca.redstonepcbs.chip.Dir;
import com.retiredroca.redstonepcbs.chip.BoardTaps;
import com.retiredroca.redstonepcbs.chip.PortCodec;
import com.retiredroca.redstonepcbs.chip.PortFlow;
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
import net.minecraft.world.level.redstone.NeighborUpdater;
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
    /**
     * The board's two redstone taps: at most one input, at most one output, both able to exist at once.
     * With no face on either -- the block is powered from whichever neighbour carries the level. Never
     * null: {@link BoardTaps#EMPTY} is the no-taps value.
     */
    private BoardTaps taps = BoardTaps.EMPTY;

    /**
     * The level the input tap's cell was last notified about, so the cells around it are only touched
     * while the bridge carries something, and once more on the tick it drops to zero. See
     * {@link #notifyPortCell}.
     */
    private int lastNotified;

    /**
     * The level this board drives into the world, from its output tap. Written once per server tick by
     * {@link #publishTaps()} and read by {@link PcbBlock#getSignal} in every direction.
     */
    private int outLevel;

    /** The last logged {@code cell:flow:level}, so the diagnostic reports changes rather than every tick. */
    @Nullable
    private String lastLoggedPort;

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
        if (!taps.isEmpty()) {
            // Refresh the levels crossing the boundary. The bridge serves what is published here, so
            // without this a signal changing in the world would never reach the board.
            publishTaps();
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

    // --- redstone bridge -------------------------------------------------------------------------

    /** This board's two taps. Never {@code null}; {@link BoardTaps#EMPTY} when it has none. */
    public BoardTaps taps() {
        return taps;
    }

    /** The tap bytes for the snapshot payload. */
    public byte[] portBytes() {
        return PortCodec.encode(taps);
    }

    /**
     * Replaces both taps, after checking each against the tap rule.
     *
     * <p>Sanitises per tap rather than rejecting the whole update: a bad cell is dropped with a log line
     * and a good one still lands, because losing a working input because the output was malformed is a
     * worse outcome than losing the malformed half. The editor refuses a bad cell before it sends, and
     * refuses to move, so this path is a stale client or a hand-edited payload.
     *
     * <p>A tap that moved or was cleared releases the cells it used to drive. Without that notification an
     * input removed while it was carrying would latch the circuit on permanently.
     *
     * @return whether the stored taps changed, so the caller knows whether to re-publish
     */
    public boolean setTaps(BoardTaps replacement) {
        BoardTaps wanted = sanitise(replacement == null ? BoardTaps.EMPTY : replacement);
        if (wanted.equals(taps)) {
            return false;
        }
        ServerLevel board = pcbLevel();
        BoardSpace space = space();
        if (board != null && space != null) {
            // Release any cell that used to be an input and no longer is, before adopting the new set.
            for (PortFlow flow : PortFlow.VALUES) {
                int before = taps.cellOf(flow);
                if (before != BoardTaps.NONE && wanted.cellOf(flow) != before) {
                    notifyPortCell(board, before, space.pos(before), 0);
                }
            }
        }
        taps = wanted;
        publishTaps();
        setChanged();
        return true;
    }

    /**
     * Drops any tap whose cell is outside the grid or is not glass, naming each in the log.
     *
     * <p>Called on load as well as on assignment, so a board saved before the glass rule existed loses the
     * tap rather than keeping one that can never fire. Said out loud, because a silently vanished port is
     * exactly the failure this project keeps paying for.
     */
    private BoardTaps sanitise(BoardTaps candidate) {
        // space() rather than grid(): grid() calls ensureRegion(), which will *allocate* a region, and
        // allocating one part-way through deserialising a block entity is not something to do by accident.
        BoardSpace space = space();
        BlockState[] grid = space != null ? GridSerializer.snapshot(space) : null;
        BoardTaps result = candidate;
        for (PortFlow flow : PortFlow.VALUES) {
            int cell = candidate.cellOf(flow);
            if (cell == BoardTaps.NONE) {
                continue;
            }
            if (cell < 0 || cell >= GridSerializer.COUNT) {
                LOGGER.warn("PCB {}: dropping the {} tap on cell {}, which is outside the grid",
                        worldPosition, flow, cell);
                result = result.with(flow, BoardTaps.NONE);
            } else if (grid != null && !TapCell.isTap(grid[cell])) {
                LOGGER.warn("PCB {}: dropping the {} tap on cell {}, which does not hold glass",
                        worldPosition, flow, cell);
                result = result.with(flow, BoardTaps.NONE);
            }
        }
        return result;
    }

    /**
     * Samples both taps and hands the results to the signal bridge, which serves them to vanilla's
     * signal reads in the board. The board dimension is where those reads happen and this block entity
     * lives in the world, so the bridge keeps its own server-scoped map keyed by level.
     *
     * <p>One entry per tap, and the two are independent: the registry already holds a map and already
     * serves only an input, so an input and an output coexist. The input is the one that needs telling
     * its neighbours the level moved, because the output is read out of the board rather than driven into
     * it.
     */
    private void publishTaps() {
        ServerLevel board = pcbLevel();
        BoardSpace space = space();
        if (board == null || space == null || level == null || taps.isEmpty()) {
            // No region means no cell positions, so there is nothing to serve. Publishing an empty map
            // also drops any taps a previous region of this board registered.
            if (board != null) {
                SignalBridge.publish(board, java.util.Map.of());
            }
            outLevel = 0;
            lastNotified = 0;
            return;
        }
        // Enforce the glass rule where it can be judged. Assignment already refuses a non-glass cell, but
        // the player can break the glass afterwards, and a tap that then outlives its own rule is worse
        // than one that stops and says so.
        BoardTaps live = requireGlass(space);
        if (!live.equals(taps)) {
            taps = live;
            setChanged();
        }
        java.util.Map<BlockPos, SignalBridge.Served> served = new java.util.LinkedHashMap<>();
        outLevel = 0;
        int inCarried = 0;
        for (PortFlow flow : PortFlow.VALUES) {
            int cell = taps.cellOf(flow);
            if (cell == BoardTaps.NONE) {
                continue;
            }
            BlockPos cellPos = space.pos(cell);
            int carried;
            if (flow == PortFlow.IN) {
                carried = worldLevelIn();
                inCarried = carried;
            } else {
                carried = sampleOutput(board, cellPos);
                outLevel = carried;
            }
            served.put(cellPos, new SignalBridge.Served(flow, carried));
            logTapChange(flow, cell, carried);
        }
        SignalBridge.publish(board, served);
        if (taps.hasIn()) {
            notifyPortCell(board, taps.cellOf(PortFlow.IN), space.pos(taps.cellOf(PortFlow.IN)), inCarried);
        }
    }

    /**
     * Drops any tap whose cell no longer holds glass, naming it. Called from {@link #publishTaps()}, where
     * the region is readable, because assignment-time and load-time checks both have a blind spot: a tap
     * can outlive the glass that justified it.
     */
    private BoardTaps requireGlass(BoardSpace space) {
        BlockState[] grid = GridSerializer.snapshot(space);
        BoardTaps result = taps;
        for (PortFlow flow : PortFlow.VALUES) {
            int cell = taps.cellOf(flow);
            if (cell == BoardTaps.NONE || TapCell.isTap(grid[cell])) {
                continue;
            }
            LOGGER.warn("PCB {}: dropping the {} tap on cell {}, which no longer holds glass",
                    worldPosition, flow, cell);
            result = result.with(flow, BoardTaps.NONE);
        }
        return result;
    }

    /**
     * The level the world delivers to this PCB, from whichever of its six neighbours carries it.
     *
     * <p>{@code getBestNeighborSignal} rather than a read of one particular neighbour, because the block
     * is powered as a block and vanilla's notion of that is every side at once — the same direction set
     * {@code HopperBlock.checkPoweredState} consults through {@code hasNeighborSignal}. It also returns a
     * level rather than a yes/no, so a dimmer source arrives dimmer. Direction-free by construction, so
     * no face can be mismatched between the editor and the world.
     */
    private int worldLevelIn() {
        return Math.clamp(level.getBestNeighborSignal(worldPosition), 0, 15);
    }

    /**
     * The level the tap is collecting from the board, for an output.
     *
     * <p>The tap sits <em>beside</em> the component rather than on it, so the emitter is normally one of
     * its six neighbours. Both the cell itself and every neighbour are read and the strongest wins, which
     * is what lets a tap be an ordinary empty cell with a wire running past it.
     *
     * <p>Symmetric with an input, and deliberately so: an input powers everything beside the tap, so an
     * output collecting from everything beside the tap is the same reach seen from the other side. A tap
     * placed next to something you did not mean to carry will carry it. That is the builder's judgement
     * to make, exactly as it is for an input.
     *
     * <p>Safe even though the bridge is served on the cell itself: {@link SignalBridge#levelAt} answers
     * {@code null} for anything but an input, so these reads are purely vanilla's and the bridge can never
     * report feeding itself.
     */
    private int sampleOutput(ServerLevel board, BlockPos cellPos) {
        int best = Math.clamp(board.getSignal(cellPos, Direction.UP), 0, 15);
        for (Direction dir : Direction.values()) {
            BlockPos neighbour = cellPos.relative(dir);
            int read = Math.clamp(board.getSignal(neighbour, dir), 0, 15);
            if (read > best) {
                best = read;
            }
        }
        return best;
    }

    /**
     * Logs a tap whenever the level it carries changes. Aimed at answering, in one launch, which of three
     * things is wrong: a level that never leaves zero means the world is not delivering, a level that
     * moves while the circuit stays dead means the bridge is not being served, and no line at all means
     * the tap never reached the server. One signature per direction, so an input and an output both
     * report without either hiding the other.
     */
    private void logTapChange(PortFlow flow, int cell, int carried) {
        String signature = flow + ":" + cell + ":" + carried;
        if (signature.equals(lastLoggedPort)) {
            return;
        }
        lastLoggedPort = signature;
        LOGGER.info("PCB {}: {} tap at cell {} carrying {}", worldPosition, flow, cell, carried);
    }

    /**
     * Tells the components around the tap that its level may have changed, so vanilla redstone beside it
     * re-reads. Only for a bridge that is actually carrying something.
     *
     * <p>Without this the bridge is invisible to the board. A redstone wire recomputes {@code POWER} in
     * exactly three places -- {@code onPlace}, {@code onRemove} and {@code neighborChanged}
     * ({@code RedStoneWireBlock}:361, 374, 404) -- and has no tick or {@code scheduleTick} at all, so a
     * wire would compute its power once, at whatever the source happened to be doing at that instant, and
     * keep it forever. This is the same reason vanilla relies on notifications rather than polling.
     *
     * <p>The tap's own cell is not notified, because the tap is a source rather than a sink: nothing
     * placed in it is ever powered by the bridge, and its contents are bypassed for emission. Only the
     * cells that read it matter.
     *
     * <p>Each neighbour is updated as though it had changed, which is what vanilla's
     * {@code updateNeighborsAt} would do -- except that in 1.21.1 that method, like
     * {@code Level.neighborChanged}, compiles to a bare {@code return}. They are vestigial, kept for
     * compatibility, and the live architecture routes updates through the {@link NeighborUpdater} held
     * on {@code Level}. Calling either is silently a no-op. Verified in the bytecode of the mapped jar,
     * not the decompiled sources, which show the same empty bodies and read as if they were implemented.
     *
     * <p>A torch beside the tap reacts, and that is correct: this notification says "your neighbour's
     * output changed", which is exactly what a torch's burnout logic is asking about. The one tick after a
     * level falls to zero still fires, so a wire that was lit actually goes out rather than latching on
     * once it had been powered.
     */
    private void notifyPortCell(ServerLevel board, int cell, BlockPos cellPos, int level) {
        if (level <= 0 && lastNotified <= 0) {
            return;
        }
        lastNotified = level;
        Block block = board.getBlockState(cellPos).getBlock();
        for (Direction dir : Direction.values()) {
            BlockPos neighbour = cellPos.relative(dir);
            NeighborUpdater.executeUpdate(board, board.getBlockState(neighbour), neighbour, block, cellPos,
                    false);
        }
    }

    /**
     * The level this board drives into the world, sampled once per server tick by
     * {@link #publishPort()}. Vanilla reads this through {@link PcbBlock#getSignal}, so a value cached
     * here is at most one tick old, the same latency a comparator reading its container has.
     *
     * <p>One level for the whole block, offered in every direction. A redstone block behaves the same
     * way, returning 15 regardless of which side is asked.
     */
    public int outputLevel() {
        return outLevel;
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
        if (!taps.isEmpty()) {
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
        taps = BoardTaps.EMPTY;
        lastLoggedPort = null;
        if (tag.contains("ports")) {
            byte[] data = tag.getByteArray("ports");
            // Only the cell index is judged here. The board lives in another dimension, and at this point
            // in a world load that region is usually not available -- so a glass check here reads an empty
            // grid and throws away every tap, which is exactly what it did. The glass rule is enforced
            // where the grid can actually be read: on assignment, and again in publishTaps once the region
            // is up. A tap saved against a cell whose glass was removed is dropped there, with a warning.
            taps = sanitise(PortCodec.decode(data));
        }
        publishTaps();
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
