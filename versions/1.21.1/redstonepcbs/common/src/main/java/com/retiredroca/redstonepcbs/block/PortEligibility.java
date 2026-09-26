package com.retiredroca.redstonepcbs.block;

import com.retiredroca.redstonepcbs.chip.CellIndex;
import com.retiredroca.redstonepcbs.chip.Dir;
import com.retiredroca.redstonepcbs.chip.Part;
import com.retiredroca.redstonepcbs.chip.PortOptions;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RedstoneSide;

import java.util.ArrayList;
import java.util.List;

/**
 * Which sides of a cell a redstone port may be assigned to, using vanilla's own notion of a connection.
 *
 * <p>A side qualifies when the block across it does not fill its space and is not already wired to this
 * cell. Air qualifies, and that is the point rather than an absence: vanilla lets a player place a
 * component against air precisely because nothing is in the way, so an empty neighbour is the clearest
 * case to bridge.
 *
 * <p>Deliberately level-free, working off the grid the editor already holds. Vanilla's own test is
 * {@code isRedstoneConductor}, which resolves to {@code isCollisionShapeFullBlock(level, pos)} and so
 * needs a level the client does not have for the board dimension. {@code canOcclude()} is the no-argument
 * vanilla proxy for the same thing — a block that fills its space — and it needs no level, so the editor
 * and the server judge a side identically and cannot disagree about what is offerable.
 *
 * <p>Only vanilla predicates are used. NeoForge has {@code canRedstoneConnectTo}, but it is a
 * NeoForge-only extension ({@code IBlockStateExtension}) with no Fabric equivalent, so relying on it
 * would make the two loaders behave differently.
 */
public final class PortEligibility {
    private PortEligibility() {}

    /**
     * The sides of cell {@code index} in {@code grid} a port may be assigned to, in the part's own
     * facing order. Out-of-grid neighbours count as air, which is what a region's outer layer really is.
     */
    public static Dir[] sides(BlockState[] grid, int index) {
        BlockState state = at(grid, index);
        if (state == null) {
            return Dir.VALUES.clone();
        }
        Dir[] candidates = PortOptions.candidateSides(BoardStates.partOf(state));
        List<Dir> open = new ArrayList<>(candidates.length);
        for (Dir dir : candidates) {
            if (isOpen(grid, index, dir)) {
                open.add(dir);
            }
        }
        return open.toArray(new Dir[0]);
    }

    /**
     * Whether a port may be assigned to {@code dir} on cell {@code index}.
     *
     * <p>Decided by what is in the neighbouring cell, not by the block's own remembered connections. A
     * wire keeps a stale side after its neighbour is cleared, because vanilla only re-shapes a wire when
     * a real neighbouring block changes — {@code neighborChanged} recomputes power, never shape — so a
     * wire can still claim a join to a cell that is now air. Reading the cell instead answers the
     * question actually being asked: is this side free for something that connects?
     */
    public static boolean isOpen(BlockState[] grid, int index, Dir dir) {
        BlockState state = at(grid, index);
        if (state == null) {
            return true;
        }
        // A side the part cannot present is never offered, whatever is across it.
        boolean presentable = false;
        for (Dir candidate : PortOptions.candidateSides(BoardStates.partOf(state))) {
            if (candidate == dir) {
                presentable = true;
                break;
            }
        }
        return presentable && cellIsFree(at(grid, CellIndex.neighbour(index, dir)));
    }

    /**
     * Whether a cell is free for a component that connects: air, or something replaceable like snow or
     * short grass. A full block is not free, and that is the case the maintainer described: a line
     * running past a block it cannot connect to stays a line and does not power it, so a port there would
     * do something vanilla never would. A cell just outside the grid is air, so it is free.
     */
    private static boolean cellIsFree(BlockState across) {
        return across == null || across.isAir() || across.canBeReplaced();
    }

    private static BlockState at(BlockState[] grid, int index) {
        if (grid == null || index < 0 || index >= grid.length) {
            return null;
        }
        return grid[index];
    }

    /** Whether a wire is already wired on {@code dir}. Other blocks are never already connected. */
}
