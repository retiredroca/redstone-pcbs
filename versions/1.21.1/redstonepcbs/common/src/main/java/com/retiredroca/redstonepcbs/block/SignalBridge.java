package com.retiredroca.redstonepcbs.block;

import com.retiredroca.redstonepcbs.chip.PortLink;
import com.retiredroca.redstonepcbs.dimension.PcbDimension;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Serves the redstone level of a board cell that is a signal port, to vanilla's own signal read.
 *
 * <p>Vanilla asks a {@code BlockState} for the level it emits toward a direction through
 * {@code BlockBehaviour.BlockStateBase#getSignal} and {@code getDirectSignal}. Those two methods are
 * declared once, on {@code BlockStateBase}, and nothing overrides them — {@code BlockState} is a
 * concrete subclass that inherits both — so intercepting them there catches every signal read in the
 * game. That is the only place a level can be substituted before the block's own logic runs, and it
 * needs no block placed in the board: the port cell already holds the redstone component the player
 * linked, and only the value read from it is replaced.
 *
 * <p>The interception happens in a per-loader mixin, which supplies the injection point and delegates
 * here. This class holds no loader-specific types, so both loaders share it.
 */
public final class SignalBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger("redstonepcbs");

    /**
     * Registered ports by board, keyed by the level that hosts them, then by the cell's block position
     * in that level. Server-scoped because the read happens in the board level, where the owning
     * {@link PcbBlockEntity} does not exist.
     */
    private static final Map<BlockGetter, Map<BlockPos, PortLink>> PORTS = new HashMap<>();

    /** Set once, the first time a board signal read is intercepted. Proof the mixin applied. */
    private static boolean seenRead;

    private SignalBridge() {}

    /**
     * The board level, identified once and then compared by identity. The mixin runs on every redstone
     * read in the game, so this sits on the hottest path there is; a non-board read must not pay for a
     * {@code ResourceKey} comparison. Identification happens on the first board read and never again.
     */
    private static BlockGetter boardLevel;

    /** Whether this level is the board dimension, and so can hold ports. */
    public static boolean isBoard(BlockGetter level) {
        if (level == boardLevel) {
            return true;
        }
        if (boardLevel == null && level instanceof Level board
                && PcbDimension.LEVEL_KEY.equals(board.dimension())) {
            boardLevel = board;
            return true;
        }
        return false;
    }

    /**
     * Replaces one board's registered ports. Called from the owning block entity; an empty map removes
     * them. Rebuilt at most once per game tick, so this is not on the hot path.
     */
    public static void publish(BlockGetter level, Map<BlockPos, PortLink> ports) {
        if (ports.isEmpty()) {
            PORTS.remove(level);
        } else {
            PORTS.put(level, new HashMap<>(ports));
        }
    }

    /** Forgets every registration. Used when a server stops so a stale level cannot answer. */
    public static void clear() {
        PORTS.clear();
        boardLevel = null;
    }

    /** How many ports are registered on a level. Diagnostics only. */
    public static int count(BlockGetter level) {
        Map<BlockPos, PortLink> ports = PORTS.get(level);
        return ports == null ? 0 : ports.size();
    }

    /**
     * The level this cell emits toward {@code dir} as a port, or {@code null} when the cell is not a
     * port and vanilla's own answer must stand.
     */
    @Nullable
    public static Integer levelAt(BlockGetter level, BlockPos pos, Direction dir, boolean direct) {
        if (!isBoard(level)) {
            return null;
        }
        markSeenRead();
        Map<BlockPos, PortLink> ports = PORTS.get(level);
        if (ports == null) {
            return null;
        }
        PortLink port = ports.get(pos);
        if (port == null) {
            return null;
        }
        // Only the linked side bridges; a read from any other side is the component's own behaviour.
        // The port records a chip Dir, so convert before comparing with the vanilla Direction.
        Direction side = Directions.toMinecraft(port.side());
        if (side != dir && side != dir.getOpposite()) {
            return null;
        }
        // Nothing is served yet. The editor cannot assign a port until the selector exists, so the
        // registry is always empty and this is unreachable in practice; serving 0 here would silently
        // flatten a live circuit. Step 3 supplies the real level.
        return null;
    }

    /**
     * Records that a board signal read was intercepted. The line appears exactly once per session and
     * is the only positive evidence that the mixin resolved: a mixin that failed to apply would
     * otherwise be indistinguishable from one that is simply doing nothing.
     */
    private static void markSeenRead() {
        if (!seenRead) {
            seenRead = true;
            LOGGER.info("PCB redstone bridge: intercepting board signal reads");
        }
    }

    /** Test/diagnostic hook: forget that a read was seen, so the log line can be observed again. */
    public static void resetSeenRead() {
        seenRead = false;
    }
}
