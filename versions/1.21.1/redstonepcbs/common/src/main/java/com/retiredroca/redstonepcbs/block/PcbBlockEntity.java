package com.retiredroca.redstonepcbs.block;

import com.retiredroca.redstonepcbs.RedstonePcbs;
import com.retiredroca.redstonepcbs.chip.Dir;
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
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.Hopper;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A board. Its circuit lives in a {@value BoardSpace#SIZE}x{@value BoardSpace#SIZE}x{@value BoardSpace#SIZE}
 * region of the runtime board dimension as real blocks, so vanilla owns the redstone. This block
 * entity owns the region allocation, the client sync, the per-board flags, and the world item
 * gateway: each face exposes the inventories of the region's edge containers so world hoppers can
 * insert/extract through the PCB block. It also ticks like a hopper, pulling from a container
 * directly above it into the board's top edge.
 */
public class PcbBlockEntity extends BlockEntity implements WorldlyContainer, Hopper {
    private static final int SIZE = BoardSpace.SIZE;
    /** Vanilla hopper cooldown between transfers, in game ticks. */
    private static final int MOVE_COOLDOWN = HopperBlockEntity.MOVE_ITEM_SPEED;

    private ChunkPos boardChunk;
    private boolean regionCleaned;
    private boolean externalInput;
    private boolean externalOutput;
    private boolean initialized;
    private BlockState[] pendingGrid;
    private long lastHash;
    /** Cached view of the boundary-layer container slots, rebuilt each game tick. */
    private List<SlotRef> boundarySlots = List.of();
    private long boundaryBuiltAt = Long.MIN_VALUE;
    private boolean loggedMissingDimension;
    private final int[] lastFaceCount = {-1, -1, -1, -1, -1, -1};
    /** Hopper-style transfer cooldown, in game ticks. */
    private int cooldownTime;

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
        boolean allocated = false;
        if (boardChunk == null) {
            BoardChunks.Slot slot = chunks.allocate(pcbLevel);
            boardChunk = slot.chunk();
            allocated = true;
            LOGGER.info("PCB {}: allocated board region {} (base Y {})",
                    worldPosition, boardChunk, BoardChunks.baseY(pcbLevel));
            setChanged();
        }
        if (!regionCleaned) {
            regionCleaned = true;
            BoardSpace space = new BoardSpace(pcbLevel, boardChunk, BoardChunks.baseY(pcbLevel));
            if (allocated) {
                // The dimension is void, but clear the region anyway so a fresh board starts empty.
                space.clear();
            } else {
                removeForeignBlocks(space);
            }
        }
        if (pendingGrid != null) {
            GridSerializer.apply(pendingGrid, new BoardSpace(pcbLevel, boardChunk, BoardChunks.baseY(pcbLevel)));
            pendingGrid = null;
        }
    }

    /** Drops generated terrain left inside the region by builds that did not wipe it on placement. */
    private void removeForeignBlocks(BoardSpace space) {
        int removed = 0;
        for (int i = 0; i < GridSerializer.COUNT; i++) {
            BlockState state = space.get(i);
            if (!state.isAir() && !BoardStates.isBoardBlock(state)) {
                space.set(BoardSpace.xOf(i), BoardSpace.yOf(i), BoardSpace.zOf(i),
                        net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                removed++;
            }
        }
        if (removed > 0) {
            LOGGER.info("PCB {}: cleared {} generated block(s) from the board region", worldPosition, removed);
        }
    }

    public void serverTick() {
        if (level == null || level.isClientSide) {
            return;
        }
        ensureRegion();
        ensureBoundarySlots();
        if (!initialized) {
            initialized = true;
            // Old saves may carry stale board power / locked neighbours from earlier builds.
            BlockState state = getBlockState();
            if (state.hasProperty(PcbBlock.POWER) && state.getValue(PcbBlock.POWER) != 0) {
                level.setBlock(worldPosition, state.setValue(PcbBlock.POWER, 0), 3);
            }
            level.updateNeighborsAt(worldPosition, state.getBlock());
        }
        if (cooldownTime < MOVE_COOLDOWN) {
            cooldownTime++;
        } else {
            // Behaves like a hopper pulling from the block directly above, whatever it faces.
            if (HopperBlockEntity.suckInItems(level, this)) {
                cooldownTime = 0;
                setChanged();
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

    public long regionHash() {
        ensureRegion();
        BoardSpace space = space();
        if (space == null) {
            return 0L;
        }
        long hash = 1;
        for (int i = 0; i < GridSerializer.COUNT; i++) {
            hash = hash * 31 + Block.getId(space.get(i));
        }
        return hash;
    }

    public boolean hasChangedSinceLastSync() {
        long hash = regionHash();
        if (hash == lastHash) {
            return false;
        }
        lastHash = hash;
        return true;
    }

    // --- flags ------------------------------------------------------------------------------------

    public boolean isExternalInput() {
        return externalInput;
    }

    public void setExternalInput(boolean value) {
        externalInput = value;
        setChanged();
    }

    public boolean isExternalOutput() {
        return externalOutput;
    }

    public void setExternalOutput(boolean value) {
        externalOutput = value;
        setChanged();
    }

    // --- lifecycle --------------------------------------------------------------------------------

    public void sendEditorTo(ServerPlayer player, Dir face) {
        RedstonePcbs.platform().sendToPlayer(player, S2COpenEditorPayload.block(worldPosition, face.ordinal()));
        RedstonePcbs.platform().sendToPlayer(player, S2CSnapshotPayload.block(worldPosition, snapshotBytes()));
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

    /**
     * Rebuilds the gateway ports. A PCB face exposes the in-board containers whose editor cell sits on
     * that face's edge layer (bottom face -> editor layer {@code y=0}, top face -> {@code y=SIZE-1},
     * sides -> their edge columns), one per grid line. Slots are taken from the container's own vanilla
     * face rules where it has them, so an in-board furnace/brewing stand keeps its real orientation.
     *
     * <p>Confirmed behaviour by face:
     * <ul>
     *   <li>BOTTOM: an in-world hopper attached under the PCB pulls from the container on the board's
     *       lowest editor layer.</li>
     *   <li>TOP: the block above may be air or a container (hopper, chest, furnace, blast furnace,
     *       smoker, brewer); the board pulls down from it.</li>
     *   <li>SIDES (north/south/west/east): receive only, and only from a directly attached hopper.</li>
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
        for (Dir face : Dir.VALUES) {
            Direction direction = Directions.toMinecraft(face);
            for (Cell c : edgeLayer(cells, face)) {
                for (int slot : slotsFor(direction, c.container())) {
                    list.add(new SlotRef(face, c.container(), slot));
                }
            }
        }
        if (list.size() != boundarySlots.size()) {
            LOGGER.info("PCB gateway {}: {} port slot(s) from {} container(s)",
                    worldPosition, list.size(), cells.size());
        }
        boundarySlots = list;
    }

    /** The containers whose editor cell lies on {@code face}'s edge layer, nearest that edge per line. */
    private static List<Cell> edgeLayer(List<Cell> cells, Dir face) {
        Map<Long, Cell> best = new HashMap<>();
        Map<Long, Integer> depth = new HashMap<>();
        for (Cell c : cells) {
            long key;
            int d;
            switch (face) {
                case UP -> {
                    key = key(c.x(), c.z());
                    d = SIZE - 1 - c.y();
                }
                case DOWN -> {
                    key = key(c.x(), c.z());
                    d = c.y();
                }
                case NORTH -> {
                    key = key(c.x(), c.y());
                    d = c.z();
                }
                case SOUTH -> {
                    key = key(c.x(), c.y());
                    d = SIZE - 1 - c.z();
                }
                case WEST -> {
                    key = key(c.y(), c.z());
                    d = c.x();
                }
                default -> {
                    key = key(c.y(), c.z());
                    d = SIZE - 1 - c.x();
                }
            }
            Integer prev = depth.get(key);
            if (prev == null || d < prev) {
                depth.put(key, d);
                best.put(key, c);
            }
        }
        return new ArrayList<>(best.values());
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

    private static long key(int a, int b) {
        return ((long) a << 8) | (b & 0xFF);
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
        return ref == null ? ItemStack.EMPTY : ref.container().removeItem(ref.slot(), amount);
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        SlotRef ref = slot(slot);
        return ref == null ? ItemStack.EMPTY : ref.container().removeItemNoUpdate(ref.slot());
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        SlotRef ref = slot(slot);
        if (ref != null) {
            ref.container().setItem(ref.slot(), stack);
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
        // board's top-edge port (the container nearest the ceiling), matching the item gateway.
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
        // A null direction is the hopper-style pull, which enters from above: top-edge port only.
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

    // --- persistence ------------------------------------------------------------------------------

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (boardChunk != null) {
            tag.putInt("chunkX", boardChunk.x);
            tag.putInt("chunkZ", boardChunk.z);
        }
        tag.putBoolean("input", externalInput);
        tag.putBoolean("output", externalOutput);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        boardChunk = tag.contains("chunkX") ? new ChunkPos(tag.getInt("chunkX"), tag.getInt("chunkZ")) : null;
        externalInput = tag.getBoolean("input");
        externalOutput = tag.getBoolean("output");
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
