package com.retiredroca.redstonepcbs.block;

import com.retiredroca.redstonepcbs.RedstonePcbs;
import com.retiredroca.redstonepcbs.block.component.BoardComponent;
import com.retiredroca.redstonepcbs.block.level.BoardLevel;
import com.retiredroca.redstonepcbs.chip.Cell;
import com.retiredroca.redstonepcbs.chip.ChipSerializer;
import com.retiredroca.redstonepcbs.chip.ChipWorld;
import com.retiredroca.redstonepcbs.chip.Dir;
import com.retiredroca.redstonepcbs.chip.Part;
import com.retiredroca.redstonepcbs.net.S2COpenEditorPayload;
import com.retiredroca.redstonepcbs.net.S2CSnapshotPayload;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;

/**
 * Holds one {@link ChipWorld} and bridges it to the world: redstone on the six faces, and an item
 * gate that lets hoppers/chests/furnaces/etc. insert and extract. Items entering the input faces are
 * routed to the filtered output (front/bottom) or the reject output (left/right) using the
 * per-hopper-component filters; the gate only routes while it is enabled by the circuit.
 */
public class PcbBlockEntity extends BlockEntity implements WorldlyContainer {
    private static final int GAME_TICKS_PER_REDSTONE_TICK = 2;
    private static final int REGION = 9;
    private static final int[] INPUT_SLOTS = slots(0);
    private static final int[] FILTERED_SLOTS = slots(REGION);
    private static final int[] REJECT_SLOTS = slots(REGION * 2);

    private ChipWorld chip = new ChipWorld();
    private final int[] lastOutput = new int[Dir.VALUES.length];
    private int gameTickCounter;
    private boolean inputsDirty = true;

    /** Container/processor parts, keyed by cell index, running vanilla block-entity logic. */
    private final Map<Integer, BoardComponent> components = new HashMap<>();
    /** Component NBT loaded before the level was available; applied when the component is created. */
    private final Map<Integer, CompoundTag> pendingComponents = new HashMap<>();
    private BoardLevel boardLevel;

    private final NonNullList<ItemStack> input = NonNullList.withSize(REGION, ItemStack.EMPTY);
    private final NonNullList<ItemStack> filtered = NonNullList.withSize(REGION, ItemStack.EMPTY);
    private final NonNullList<ItemStack> reject = NonNullList.withSize(REGION, ItemStack.EMPTY);
    private final Map<Integer, ItemStack> filters = new HashMap<>();

    public PcbBlockEntity(BlockPos pos, BlockState state) {
        super(RedstonePcbs.platform().pcbBlockEntityType(), pos, state);
    }

    private static int[] slots(int offset) {
        int[] out = new int[REGION];
        for (int i = 0; i < REGION; i++) {
            out[i] = offset + i;
        }
        return out;
    }

    public ChipWorld chip() {
        return chip;
    }

    public void setChip(ChipWorld chip) {
        this.chip = chip;
        markInputsDirty();
        setChanged();
    }

    public void markInputsDirty() {
        inputsDirty = true;
    }

    /** Sets (or clears, when empty) the item filter held by a hopper component cell. */
    public void setFilter(int cellIndex, ItemStack filter) {
        if (filter.isEmpty()) {
            filters.remove(cellIndex);
        } else {
            filters.put(cellIndex, filter.copyWithCount(1));
        }
        setChanged();
    }

    public ItemStack filter(int cellIndex) {
        return filters.getOrDefault(cellIndex, ItemStack.EMPTY);
    }

    /** Called after an editor or blueprint edit; syncs to clients and schedules a world update. */
    public void onEdited() {
        setChanged();
        markInputsDirty();
        if (level != null) {
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, 3);
        }
    }

    public void sendEditorTo(ServerPlayer player, Dir face) {
        RedstonePcbs.platform().sendToPlayer(player, S2COpenEditorPayload.block(worldPosition, face.ordinal()));
        RedstonePcbs.platform().sendToPlayer(player,
                S2CSnapshotPayload.block(worldPosition, ChipSerializer.write(chip)));
    }

    public void serverTick() {
        if (level == null || level.isClientSide) {
            return;
        }
        ensureComponents();
        tickComponents();
        routeItems();
        gameTickCounter++;
        if (gameTickCounter < GAME_TICKS_PER_REDSTONE_TICK && !inputsDirty) {
            return;
        }
        gameTickCounter = 0;
        sampleInputs();
        if (chip.isActive()) {
            chip.tick();
            setChanged();
        }
        pushOutputs();
    }

    // --- container/processor components -----------------------------------------------------------

    /** The component at {@code cell}, or null if the cell holds no container/processor part. */
    public BoardComponent component(int cell) {
        return components.get(cell);
    }

    /** The vanilla block entity of the component at {@code cell}, for the virtual board level. */
    public BlockEntity componentEntity(int cell) {
        BoardComponent component = components.get(cell);
        return component == null ? null : component.blockEntity();
    }

    /** The menu provider of the component at {@code cell}, or null. */
    public MenuProvider componentMenu(int cell) {
        BoardComponent component = components.get(cell);
        return component == null ? null : component.menuProvider();
    }

    /** Called when a component mutates (or a captured block update arrives). */
    public void onComponentChanged() {
        setChanged();
    }

    /** Creates/removes components so the map matches the chip and the current hopper mode. */
    private void ensureComponents() {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (boardLevel == null || boardLevel.real() != serverLevel) {
            boardLevel = new BoardLevel(serverLevel, this);
            components.clear();
        }
        boolean simple = chip.isSimpleHopperMode();
        for (int i = 0; i < chip.cellCount(); i++) {
            Part part = chip.cell(i).part;
            boolean wanted = part.isContainer() && !(part == Part.HOPPER && simple);
            BoardComponent existing = components.get(i);
            if (!wanted) {
                if (existing != null) {
                    if (existing.part() == Part.HOPPER && part == Part.HOPPER) {
                        // Only the hopper mode changed: keep the inventory for when it returns.
                        pendingComponents.put(i,
                                existing.blockEntity().saveWithoutMetadata(serverLevel.registryAccess()));
                    } else {
                        dropContents(existing);
                    }
                    components.remove(i);
                }
                continue;
            }
            if (existing != null && existing.part() == part) {
                continue;
            }
            BlockPos pos = boardLevel.vposOf(i);
            BoardComponent created = BoardComponent.create(part, pos, com.retiredroca.redstonepcbs.block.BoardStates.of(chip.cell(i)));
            if (created == null) {
                continue;
            }
            created.blockEntity().setLevel(boardLevel);
            CompoundTag saved = pendingComponents.remove(i);
            if (saved != null) {
                created.blockEntity().loadWithComponents(saved, serverLevel.registryAccess());
            }
            components.put(i, created);
        }
    }

    private void tickComponents() {
        for (Map.Entry<Integer, BoardComponent> entry : components.entrySet()) {
            Cell cell = chip.cell(entry.getKey());
            entry.getValue().tick(boardLevel, entry.getKey(), cell);
        }
    }

    /** Spills every component's inventory into the world (called when the board is broken). */
    public void dropComponentContents() {
        for (BoardComponent component : components.values()) {
            dropContents(component);
        }
        components.clear();
    }

    /** Spills a removed component's inventory into the world so items are never silently lost. */
    private void dropContents(BoardComponent component) {
        if (!(level instanceof ServerLevel serverLevel)
                || !(component.blockEntity() instanceof Container container)) {
            return;
        }
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (!stack.isEmpty()) {
                Block.popResource(serverLevel, worldPosition, stack.copy());
                container.setItem(slot, ItemStack.EMPTY);
            }
        }
    }

    // --- item gate --------------------------------------------------------------------------------

    /** The gate routes when it has no hopper components, or when a hopper component is powered. */
    private boolean gateEnabled() {
        boolean hasHopper = false;
        for (int i = 0; i < chip.cellCount(); i++) {
            if (chip.cell(i).part == Part.HOPPER) {
                hasHopper = true;
                if (chip.cell(i).powered) {
                    return true;
                }
            }
        }
        return !hasHopper;
    }

    private void routeItems() {
        if (!gateEnabled()) {
            return;
        }
        for (int i = 0; i < REGION; i++) {
            ItemStack stack = input.get(i);
            if (stack.isEmpty()) {
                continue;
            }
            NonNullList<ItemStack> target = matchesFilter(stack) ? filtered : reject;
            if (moveInto(target, stack)) {
                input.set(i, ItemStack.EMPTY);
                setChanged();
            }
        }
    }

    private boolean matchesFilter(ItemStack stack) {
        if (filters.isEmpty()) {
            return true;
        }
        for (ItemStack filter : filters.values()) {
            if (!filter.isEmpty() && ItemStack.isSameItem(filter, stack)) {
                return true;
            }
        }
        return false;
    }

    private static boolean moveInto(NonNullList<ItemStack> target, ItemStack stack) {
        for (int j = 0; j < target.size(); j++) {
            ItemStack existing = target.get(j);
            if (existing.isEmpty()) {
                target.set(j, stack.copy());
                stack.setCount(0);
                return true;
            }
            if (ItemStack.isSameItemSameComponents(existing, stack)
                    && existing.getCount() < existing.getMaxStackSize()) {
                int move = Math.min(stack.getCount(), existing.getMaxStackSize() - existing.getCount());
                existing.grow(move);
                stack.shrink(move);
                if (stack.isEmpty()) {
                    return true;
                }
            }
        }
        return stack.isEmpty();
    }

    private static Dir chipDir(Direction direction) {
        return Directions.toChip(direction);
    }

    private static boolean isInput(Dir d) {
        return d == Dir.UP || d == Dir.NORTH;
    }

    private static boolean isFilteredOut(Dir d) {
        return d == Dir.DOWN || d == Dir.SOUTH;
    }

    private static boolean isRejectOut(Dir d) {
        return d == Dir.EAST || d == Dir.WEST;
    }

    // --- Container --------------------------------------------------------------------------------

    @Override
    public int getContainerSize() {
        return REGION * 3;
    }

    @Override
    public boolean isEmpty() {
        for (ItemStack stack : input) {
            if (!stack.isEmpty()) {
                return false;
            }
        }
        for (ItemStack stack : filtered) {
            if (!stack.isEmpty()) {
                return false;
            }
        }
        for (ItemStack stack : reject) {
            if (!stack.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private NonNullList<ItemStack> region(int slot) {
        if (slot < REGION) {
            return input;
        }
        return slot < REGION * 2 ? filtered : reject;
    }

    @Override
    public ItemStack getItem(int slot) {
        NonNullList<ItemStack> region = region(slot);
        int index = slot % REGION;
        return region.get(index);
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        NonNullList<ItemStack> region = region(slot);
        int index = slot % REGION;
        ItemStack stack = region.get(index);
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack result = stack.split(amount);
        if (stack.isEmpty()) {
            region.set(index, ItemStack.EMPTY);
        }
        setChanged();
        return result;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        NonNullList<ItemStack> region = region(slot);
        int index = slot % REGION;
        ItemStack stack = region.get(index);
        region.set(index, ItemStack.EMPTY);
        return stack;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        NonNullList<ItemStack> region = region(slot);
        int index = slot % REGION;
        region.set(index, stack);
        if (stack.getCount() > stack.getMaxStackSize()) {
            stack.setCount(stack.getMaxStackSize());
        }
        setChanged();
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return slot < REGION;
    }

    @Override
    public boolean stillValid(Player player) {
        return level != null && level.getBlockEntity(worldPosition) == this
                && player.distanceToSqr(worldPosition.getX() + 0.5, worldPosition.getY() + 0.5,
                        worldPosition.getZ() + 0.5) <= 64.0;
    }

    @Override
    public void clearContent() {
        input.clear();
        filtered.clear();
        reject.clear();
    }

    @Override
    public void setChanged() {
        super.setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    @Override
    public int[] getSlotsForFace(Direction direction) {
        Dir d = chipDir(direction);
        if (isInput(d)) {
            return INPUT_SLOTS;
        }
        if (isFilteredOut(d)) {
            return FILTERED_SLOTS;
        }
        if (isRejectOut(d)) {
            return REJECT_SLOTS;
        }
        return new int[0];
    }

    @Override
    public boolean canPlaceItemThroughFace(int slot, ItemStack stack, Direction direction) {
        return slot < REGION && isInput(chipDir(direction));
    }

    @Override
    public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction direction) {
        Dir d = chipDir(direction);
        if (slot >= REGION && slot < REGION * 2) {
            return isFilteredOut(d);
        }
        if (slot >= REGION * 2) {
            return isRejectOut(d);
        }
        return false;
    }

    // --- redstone ---------------------------------------------------------------------------------

    private void sampleInputs() {
        inputsDirty = false;
        for (Dir face : Dir.VALUES) {
            BlockPos neighbour = worldPosition.relative(Directions.toMinecraft(face));
            int signal = level.getSignal(neighbour, Directions.toMinecraft(face).getOpposite());
            chip.setFaceInput(face, signal);
            BlockState neighbourState = level.getBlockState(neighbour);
            int analog = neighbourState.hasAnalogOutputSignal()
                    ? neighbourState.getAnalogOutputSignal(level, neighbour)
                    : 0;
            chip.setFaceAnalog(face, analog);
        }
    }

    private void pushOutputs() {
        boolean changed = false;
        int max = 0;
        for (Dir face : Dir.VALUES) {
            int output = chip.getFaceOutput(face);
            max = Math.max(max, output);
            if (output != lastOutput[face.ordinal()]) {
                lastOutput[face.ordinal()] = output;
                changed = true;
            }
        }
        if (!changed) {
            return;
        }
        BlockState state = getBlockState();
        if (state.getValue(PcbBlock.POWER) != max) {
            // Changing the state fires neighbour shape updates, which is how observers detect us.
            level.setBlock(worldPosition, state.setValue(PcbBlock.POWER, max), Block.UPDATE_ALL);
        } else {
            level.updateNeighborsAt(worldPosition, state.getBlock());
        }
        setChanged();
    }

    // --- persistence ------------------------------------------------------------------------------

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putByteArray("chip", ChipSerializer.write(chip));
        ListTag filterTags = new ListTag();
        for (Map.Entry<Integer, ItemStack> entry : filters.entrySet()) {
            CompoundTag filterTag = new CompoundTag();
            filterTag.putInt("i", entry.getKey());
            filterTag.put("s", entry.getValue().save(registries));
            filterTags.add(filterTag);
        }
        tag.put("filters", filterTags);

        ListTag componentTags = new ListTag();
        for (Map.Entry<Integer, BoardComponent> entry : components.entrySet()) {
            CompoundTag componentTag = new CompoundTag();
            componentTag.putInt("i", entry.getKey());
            componentTag.putInt("p", entry.getValue().part().ordinal());
            componentTag.put("be", entry.getValue().blockEntity().saveWithoutMetadata(registries));
            componentTags.add(componentTag);
        }
        for (Map.Entry<Integer, CompoundTag> entry : pendingComponents.entrySet()) {
            CompoundTag componentTag = new CompoundTag();
            componentTag.putInt("i", entry.getKey());
            componentTag.putInt("p", chip.cell(entry.getKey()).part.ordinal());
            componentTag.put("be", entry.getValue().copy());
            componentTags.add(componentTag);
        }
        tag.put("components", componentTags);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("chip")) {
            chip = ChipSerializer.read(tag.getByteArray("chip"));
        }
        filters.clear();
        ListTag filterTags = tag.getList("filters", Tag.TAG_COMPOUND);
        for (int i = 0; i < filterTags.size(); i++) {
            CompoundTag filterTag = filterTags.getCompound(i);
            ItemStack.parse(registries, filterTag.getCompound("s"))
                    .ifPresent(stack -> filters.put(filterTag.getInt("i"), stack));
        }
        components.clear();
        pendingComponents.clear();
        boardLevel = null;
        ListTag componentTags = tag.getList("components", Tag.TAG_COMPOUND);
        for (int i = 0; i < componentTags.size(); i++) {
            CompoundTag componentTag = componentTags.getCompound(i);
            pendingComponents.put(componentTag.getInt("i"), componentTag.getCompound("be").copy());
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putByteArray("chip", ChipSerializer.write(chip));
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
