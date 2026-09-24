package com.retiredroca.redstonepcbs.block;

import com.retiredroca.redstonepcbs.dimension.PcbDimension;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Allocates one chunk per board inside the board dimension {@link PcbDimension}.
 *
 * <p>Boards are laid out on a stride-2 grid, so every board is separated from the next by one clear
 * chunk in all directions (including diagonals). The board's own chunk is held <em>ticking</em> so
 * its redstone runs with no player nearby, and the 8 neighbours are held <em>lazy-loaded</em> so the
 * cleared border around each board stays loaded without ticking. Tickets are refcounted, so the ring
 * chunks shared by adjacent boards are only released when the last board that needs them is gone.
 */
public final class BoardChunks extends SavedData {
    private static final String NAME = "redstonepcbs_boards";

    /**
     * Region ticket that keeps a board chunk loading and ticking with no player nearby. Distance 2
     * gives ticket level {@code ChunkLevel.byStatus(FULL) - 2 = 31} (entity ticking), which satisfies
     * {@code shouldTickBlocksAt} (level &le; 32) so the chunk's scheduled redstone ticks run. The
     * plain {@link TicketType#create} form and the four-argument {@code addRegionTicket} are the same
     * on both loaders (the remote-access terminal uses this exact pattern), unlike NeoForge's
     * force-ticks overload.
     */
    private static final TicketType<ChunkPos> BOARD = TicketType.create("redstonepcbs:board",
            Comparator.comparingLong(ChunkPos::toLong));
    /** Ticket keeping a border chunk loaded without ticking it. */
    private static final TicketType<ChunkPos> BORDER = TicketType.create("redstonepcbs:board_border",
            Comparator.comparingLong(ChunkPos::toLong));

    /** Ticket distance that makes a chunk fully tick (level 31). */
    private static final int TICKING_DISTANCE = 2;
    /** Ticket distance that keeps a chunk loaded without ticking (level 33). */
    private static final int LAZY_DISTANCE = 0;
    /** Gap in chunks between boards, so each board has a clear neighbour ring. */
    private static final int STRIDE = 2;

    public record Slot(ChunkPos chunk) {}

    private record Key(int x, int z) {}

    private final Set<Key> used = new HashSet<>();
    /** Refcounts for ticking tickets, keyed by packed chunk pos. */
    private final Map<Long, Integer> tickingRefs = new HashMap<>();
    /** Refcounts for lazy border tickets, keyed by packed chunk pos. */
    private final Map<Long, Integer> borderRefs = new HashMap<>();
    private int next;
    private boolean restored;

    public static BoardChunks get(ServerLevel level) {
        BoardChunks data = level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(BoardChunks::new, BoardChunks::load, null), NAME);
        data.restore(level);
        return data;
    }

    /** The world Y where a board region's bottom layer sits, inside the board dimension. */
    public static int baseY(ServerLevel level) {
        return PcbDimension.BOARD_BASE_Y;
    }

    public Slot allocate(ServerLevel level) {
        while (true) {
            ChunkPos chunk = chunkFor(next);
            next++;
            if (used.add(new Key(chunk.x, chunk.z))) {
                retain(level, chunk);
                setDirty();
                return new Slot(chunk);
            }
        }
    }

    public void free(ServerLevel level, Slot slot) {
        used.remove(new Key(slot.chunk().x, slot.chunk().z));
        release(level, slot.chunk());
        setDirty();
    }

    /** Stride-2 grid walk: boards never share an edge or corner, leaving a one-chunk gap. */
    private static ChunkPos chunkFor(int index) {
        return new ChunkPos(index * STRIDE, 0);
    }

    /** Rebuilds refcounts and re-applies every ticket once, after a reload. */
    private void restore(ServerLevel level) {
        if (restored) {
            return;
        }
        restored = true;
        tickingRefs.clear();
        borderRefs.clear();
        for (Key key : used) {
            ChunkPos chunk = new ChunkPos(key.x(), key.z());
            tickingRefs.merge(ChunkPos.asLong(chunk.x, chunk.z), 1, Integer::sum);
            for (ChunkPos border : neighbours(chunk)) {
                borderRefs.merge(ChunkPos.asLong(border.x, border.z), 1, Integer::sum);
            }
        }
        for (long packed : Set.copyOf(tickingRefs.keySet())) {
            ChunkPos chunk = new ChunkPos(packed);
            addTicket(level, chunk, TICKING_DISTANCE, BOARD);
        }
        for (long packed : Set.copyOf(borderRefs.keySet())) {
            ChunkPos chunk = new ChunkPos(packed);
            addTicket(level, chunk, LAZY_DISTANCE, BORDER);
        }
    }

    private void retain(ServerLevel level, ChunkPos chunk) {
        if (tickingRefs.merge(ChunkPos.asLong(chunk.x, chunk.z), 1, Integer::sum) == 1) {
            addTicket(level, chunk, TICKING_DISTANCE, BOARD);
        }
        for (ChunkPos border : neighbours(chunk)) {
            if (borderRefs.merge(ChunkPos.asLong(border.x, border.z), 1, Integer::sum) == 1) {
                addTicket(level, border, LAZY_DISTANCE, BORDER);
            }
        }
    }

    private void release(ServerLevel level, ChunkPos chunk) {
        if (decrement(tickingRefs, chunk)) {
            removeTicket(level, chunk, TICKING_DISTANCE, BOARD);
        }
        for (ChunkPos border : neighbours(chunk)) {
            if (decrement(borderRefs, border)) {
                removeTicket(level, border, LAZY_DISTANCE, BORDER);
            }
        }
    }

    /** Decrements a refcount; returns true when it reached zero and the ticket should be removed. */
    private static boolean decrement(Map<Long, Integer> refs, ChunkPos chunk) {
        long key = ChunkPos.asLong(chunk.x, chunk.z);
        Integer count = refs.get(key);
        if (count == null) {
            return false;
        }
        if (count <= 1) {
            refs.remove(key);
            return true;
        }
        refs.put(key, count - 1);
        return false;
    }

    /** The 8 surrounding chunks (edges and diagonals) of {@code chunk}. */
    private static ChunkPos[] neighbours(ChunkPos chunk) {
        ChunkPos[] out = new ChunkPos[8];
        int i = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                out[i++] = new ChunkPos(chunk.x + dx, chunk.z + dz);
            }
        }
        return out;
    }

    private static void addTicket(ServerLevel level, ChunkPos chunk, int distance, TicketType<ChunkPos> type) {
        level.getChunkSource().addRegionTicket(type, chunk, distance, chunk);
    }

    private static void removeTicket(ServerLevel level, ChunkPos chunk, int distance, TicketType<ChunkPos> type) {
        level.getChunkSource().removeRegionTicket(type, chunk, distance, chunk);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("next", next);
        ListTag slots = new ListTag();
        for (Key key : used) {
            CompoundTag entry = new CompoundTag();
            entry.putInt("x", key.x());
            entry.putInt("z", key.z());
            slots.add(entry);
        }
        tag.put("used", slots);
        return tag;
    }

    private static BoardChunks load(CompoundTag tag, HolderLookup.Provider registries) {
        BoardChunks data = new BoardChunks();
        data.next = tag.getInt("next");
        ListTag slots = tag.getList("used", Tag.TAG_COMPOUND);
        for (int i = 0; i < slots.size(); i++) {
            CompoundTag entry = slots.getCompound(i);
            data.used.add(new Key(entry.getInt("x"), entry.getInt("z")));
        }
        return data;
    }
}
