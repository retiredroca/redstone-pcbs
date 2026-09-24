package com.retiredroca.redstonepcbs.block;

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
    public static final int SIZE = 16;

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
        return (y * SIZE + z) * SIZE + x;
    }

    public static int xOf(int index) {
        return index % SIZE;
    }

    public static int yOf(int index) {
        return index / (SIZE * SIZE);
    }

    public static int zOf(int index) {
        return (index / SIZE) % SIZE;
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

    public void set(int x, int y, int z, BlockState state) {
        level.setBlock(pos(x, y, z), state, Block.UPDATE_ALL);
    }

    public BlockEntity blockEntity(int index) {
        return level.getBlockEntity(pos(index));
    }

    /** The backing chunk in the board level. */
    public net.minecraft.world.level.chunk.LevelChunk levelChunk() {
        return level.getChunk(chunk.x, chunk.z);
    }

    /** Clears every cell to air. */
    public void clear() {
        for (int i = 0; i < SIZE * SIZE * SIZE; i++) {
            if (!get(i).isAir()) {
                level.setBlock(pos(i), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
    }
}
