package com.retiredroca.redstonepcbs.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Compact palette + 12-bit index serialization of a board's block states (for items and designs). */
public final class GridSerializer {
    public static final int COUNT = BoardSpace.SIZE * BoardSpace.SIZE * BoardSpace.SIZE;

    private GridSerializer() {}

    public static BlockState[] emptyGrid() {
        BlockState[] grid = new BlockState[COUNT];
        java.util.Arrays.fill(grid, Blocks.AIR.defaultBlockState());
        return grid;
    }

    public static BlockState[] snapshot(BoardSpace space) {
        BlockState[] grid = new BlockState[COUNT];
        for (int i = 0; i < COUNT; i++) {
            grid[i] = space.get(i);
        }
        return grid;
    }

    public static byte[] write(BlockState[] grid) {
        Map<BlockState, Integer> ids = new HashMap<>();
        List<BlockState> palette = new ArrayList<>();
        int[] cells = new int[COUNT];
        for (int i = 0; i < COUNT; i++) {
            BlockState state = grid[i] == null ? Blocks.AIR.defaultBlockState() : grid[i];
            Integer id = ids.get(state);
            if (id == null) {
                id = palette.size();
                ids.put(state, id);
                palette.add(state);
            }
            cells[i] = id;
        }
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        for (BlockState state : palette) {
            list.add(NbtUtils.writeBlockState(state));
        }
        tag.put("palette", list);
        tag.putByteArray("cells", pack(cells));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            NbtIo.writeCompressed(tag, out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    public static BlockState[] read(byte[] data, HolderGetter<Block> blocks) {
        if (data == null || data.length == 0) {
            return emptyGrid();
        }
        try {
            CompoundTag tag = NbtIo.readCompressed(new ByteArrayInputStream(data), NbtAccounter.unlimitedHeap());
            ListTag list = tag.getList("palette", 10);
            List<BlockState> palette = new ArrayList<>(list.size());
            for (int i = 0; i < list.size(); i++) {
                palette.add(NbtUtils.readBlockState(blocks, list.getCompound(i)));
            }
            int[] cells = unpack(tag.getByteArray("cells"));
            BlockState[] grid = new BlockState[COUNT];
            for (int i = 0; i < COUNT; i++) {
                int id = cells[i];
                grid[i] = id >= 0 && id < palette.size() ? palette.get(id) : Blocks.AIR.defaultBlockState();
            }
            return grid;
        } catch (IOException e) {
            return emptyGrid();
        }
    }

    /**
     * Applies a grid to a board region. Each changed cell is placed with the same flag the editor's
     * per-cell placement uses ({@code UPDATE_NEIGHBORS | UPDATE_CLIENTS}), so every cell fires the
     * standard vanilla neighbour and shape pass here and now, exactly as if the block had been
     * placed in a normal chunk. Redstone propagation therefore happens in the board dimension where
     * the block sits, and the grid never sends its own signals through the region.
     */
    public static void apply(BlockState[] grid, BoardSpace space) {
        ServerLevel level = space.level();
        java.util.List<Integer> touched = new java.util.ArrayList<>();
        for (int i = 0; i < COUNT; i++) {
            BlockState state = grid[i] == null ? Blocks.AIR.defaultBlockState() : grid[i];
            BlockPos pos = space.pos(i);
            if (!state.equals(level.getBlockState(pos))) {
                level.setBlock(pos, state, Block.UPDATE_NEIGHBORS | Block.UPDATE_CLIENTS);
                touched.add(i);
            }
        }
        // A placed cell re-shapes its neighbours through the shape pass in setBlock, but a wire keeps a
        // stale connection when the cell it was joined to is merely cleared, because vanilla recomputes
        // a wire's power on neighbourChanged and never its shape. Left alone that stale state is then
        // snapshotted back into the grid and never corrected, so re-derive it here through vanilla's own
        // pass. See BoardSpace#refreshShapes.
        space.refreshShapes(touched);
    }

    private static byte[] pack(int[] cells) {
        byte[] out = new byte[cells.length * 3 / 2];
        int p = 0;
        for (int i = 0; i < cells.length; i += 2) {
            int a = cells[i] & 0xFFF;
            int b = i + 1 < cells.length ? cells[i + 1] & 0xFFF : 0;
            out[p++] = (byte) (a & 0xFF);
            out[p++] = (byte) (((a >> 8) & 0x0F) | ((b & 0x0F) << 4));
            out[p++] = (byte) ((b >> 4) & 0xFF);
        }
        return out;
    }

    private static int[] unpack(byte[] data) {
        int[] cells = new int[COUNT];
        int p = 0;
        for (int i = 0; i < COUNT; i += 2) {
            if (p + 2 >= data.length) {
                break;
            }
            int b0 = data[p++] & 0xFF;
            int b1 = data[p++] & 0xFF;
            int b2 = data[p++] & 0xFF;
            cells[i] = b0 | ((b1 & 0x0F) << 8);
            if (i + 1 < COUNT) {
                cells[i + 1] = (b1 >> 4) | (b2 << 4);
            }
        }
        return cells;
    }
}
