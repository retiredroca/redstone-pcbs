package com.retiredroca.redstonepcbs.client;

import com.retiredroca.redstonepcbs.block.BoardStates;
import com.retiredroca.redstonepcbs.chip.Cell;

import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;

/** Caches {@link BoardStates#of(Cell)} so the editor does not rebuild a state per cell per frame. */
public final class PcbBlockStates {
    private static final Map<Long, BlockState> CACHE = new HashMap<>();

    private PcbBlockStates() {}

    public static BlockState stateFor(Cell cell) {
        long key = (cell.part.ordinal() & 0x1F)
                | ((long) (cell.facing.ordinal() & 0x7) << 5)
                | ((long) (cell.power & 0xF) << 8)
                | ((cell.powered ? 1L : 0L) << 12)
                | ((cell.subtract ? 1L : 0L) << 13)
                | ((cell.on ? 1L : 0L) << 14)
                | ((long) (cell.dustMask & 0xF) << 15)
                | ((long) (cell.delay & 0x7) << 19)
                | ((long) (cell.dustUpMask & 0xF) << 22);
        return CACHE.computeIfAbsent(key, k -> BoardStates.of(cell));
    }
}
