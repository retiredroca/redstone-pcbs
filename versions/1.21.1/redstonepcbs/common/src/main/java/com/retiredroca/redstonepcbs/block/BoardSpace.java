package com.retiredroca.redstonepcbs.block;

import com.retiredroca.redstonepcbs.chip.CellIndex;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A {@value #SIZE}x{@value #SIZE}x{@value #SIZE} view over a region of the board level. The blocks
 * are real, so Minecraft runs the redstone, neighbour updates, scheduled ticks and block entities
 * for us. Cell {@code (x,y,z)} maps to {@code (chunkMinX+x, baseY+y, chunkMinZ+z)}.
 */
public final class BoardSpace {
    /** Editable grid edge length. */
    public static final int SIZE = CellIndex.SIZE;

    private final ServerLevel level;
    private final ChunkPos chunk;
    private final int baseY;

    public BoardSpace(ServerLevel level, ChunkPos chunk, int baseY) {
        this.level = level;
        this.chunk = chunk;
        this.baseY = baseY;
    }

    public ServerLevel level() {
        return level;
    }

    public ChunkPos chunk() {
        return chunk;
    }

    /** The world Y of the region's bottom layer. */
    public int baseY() {
        return baseY;
    }

    public static int index(int x, int y, int z) {
        return CellIndex.index(x, y, z);
    }

    public static int xOf(int index) {
        return CellIndex.xOf(index);
    }

    public static int yOf(int index) {
        return CellIndex.yOf(index);
    }

    public static int zOf(int index) {
        return CellIndex.zOf(index);
    }

    public BlockPos pos(int x, int y, int z) {
        return new BlockPos(chunk.getMinBlockX() + x, baseY + y, chunk.getMinBlockZ() + z);
    }

    public BlockPos pos(int index) {
        return pos(xOf(index), yOf(index), zOf(index));
    }

    public BlockState get(int index) {
        return level.getBlockState(pos(index));
    }

    public BlockState get(int x, int y, int z) {
        return level.getBlockState(pos(x, y, z));
    }

    /**
     * Places a cell at placement time. {@code UPDATE_NEIGHBORS} fires the standard neighbour and
     * shape update pass for this one cell (exactly the "update neighbour instead of update all"
     * change from the old era), so freshly placed wires connect and their redstone reacts here
     * and now. {@code UPDATE_CLIENTS} keeps the client in sync. Whole-grid commits
     * {@link GridSerializer#apply} go through their own path and are deliberately untouched so the
     * two mechanisms stay isolated.
     */
    public void set(int x, int y, int z, BlockState state) {
        level.setBlock(pos(x, y, z), state, Block.UPDATE_NEIGHBORS | Block.UPDATE_CLIENTS);
    }

    public BlockEntity blockEntity(int index) {
        return level.getBlockEntity(pos(index));
    }

    /** The backing chunk in the board level. */
    public net.minecraft.world.level.chunk.LevelChunk levelChunk() {
        return level.getChunk(chunk.x, chunk.z);
    }

    /**
     * Clears every cell to air, then lets the world re-derive the shapes it left behind.
     *
     * <p>Clearing a cell only re-shapes a neighbouring wire if that cell was really placed; see
     * {@link #refreshShapes(int[])}. A wire can otherwise be left claiming a join to air, which is a
     * stale state on a redstone board and not merely cosmetic.
     */
    public void clear() {
        java.util.List<Integer> touched = new java.util.ArrayList<>();
        for (int i = 0; i < SIZE * SIZE * SIZE; i++) {
            if (!get(i).isAir()) {
                level.setBlock(pos(i), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                touched.add(i);
            }
        }
        refreshShapes(touched);
    }

    /**
     * Re-derives the shape of every cell in the whole grid and of their neighbours, and reports how many
     * cells' shapes actually changed. Used once per region to repair a board saved while stale
     * connections were possible, and to make the repair observable rather than silent.
     */
    public int refreshAllShapes() {
        java.util.List<Integer> all = new java.util.ArrayList<>(SIZE * SIZE * SIZE);
        for (int i = 0; i < SIZE * SIZE * SIZE; i++) {
            all.add(i);
        }
        return refreshShapes(all);
    }

    /**
     * Re-derives the shape of every cell in {@code changed} and of its six neighbours, by invoking
     * vanilla's own shape pass rather than re-deriving anything by hand.
     *
     * <p>Why this is needed: a wire's connections live in its own blockstate, and vanilla only
     * recomputes them when a <em>real neighbouring block changes</em> — a wire's
     * {@code neighborChanged} recomputes {@code POWER} and never shape. So removing a block that a wire
     * was joined to leaves the wire still claiming the join, and because a board's grid is snapshotted
     * from the world and written back, that stale state is then round-tripped and never corrected.
     *
     * <p>{@code updateNeighbourShapes} is the same call {@code Level.setBlock} makes in its shape pass,
     * and {@code updateIndirectNeighbourShapes} the one it makes for diagonals — which is what a wire
     * uses for its sloped connections. Calling vanilla's own pass keeps the result identical to what
     * the world would have produced, rather than a reimplementation that can disagree with it.
     */
    public int refreshShapes(java.util.Collection<Integer> changed) {
        if (changed.isEmpty()) {
            return 0;
        }
        java.util.Set<Integer> seeds = new java.util.LinkedHashSet<>();
        for (int index : changed) {
            if (index < 0 || index >= SIZE * SIZE * SIZE) {
                continue;
            }
            seeds.add(index);
            for (com.retiredroca.redstonepcbs.chip.Dir dir : com.retiredroca.redstonepcbs.chip.Dir.VALUES) {
                int neighbour = com.retiredroca.redstonepcbs.chip.CellIndex.neighbour(index, dir);
                if (neighbour >= 0) {
                    seeds.add(neighbour);
                }
            }
        }
        int reshaped = 0;
        for (int index : seeds) {
            BlockPos p = pos(index);
            BlockState before = level.getBlockState(p);
            // The recursive form decrements the shape recursion budget, so a shape change that cascades
            // further reaches its own neighbours exactly as it would have during a placement.
            before.updateNeighbourShapes(level, p, 0);
            before.updateIndirectNeighbourShapes(level, p, 0);
            if (!before.equals(level.getBlockState(p))) {
                reshaped++;
            }
        }
        return reshaped;
    }

    /**
     * Ensures the smooth-stone layer one block below the grid. Sits at {@code relativeY = -1}
     * (world {@code baseY-1}) so it never occupies a cell of the usable 16x16x16 region, and it is
     * outside every {@link #clear()} / region-sweep path (those only iterate the grid cells), so it
     * survives reload unharmed and is idempotent. Placement uses {@code UPDATE_CLIENTS} only: the
     * floor is structural, not redstone, and must not itself fire neighbour updates.
     */
    public void ensureFloor() {
        BlockState floor = Blocks.SMOOTH_STONE.defaultBlockState();
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                BlockPos p = pos(x, -1, z);
                if (!level.getBlockState(p).equals(floor)) {
                    level.setBlock(p, floor, Block.UPDATE_CLIENTS);
                }
            }
        }
    }
}
