package com.retiredroca.redstonepcbs.block;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Which cells may carry a redstone tap: glass or tinted glass, and nothing else.
 *
 * <p>Glass earns the place rather than being a convenient pick:
 *
 * <ul>
 *   <li><b>It is visible.</b> Every other block in the grid is one of dozens that look alike in a dense
 *       circuit, so a port would be an invisible annotation on a cell you had to remember. Glass reads as
 *       a deliberate fitting, and the editor also outlines the tapped cell in its direction's colour.
 *   <li><b>It is transparent,</b> so the join either side of it stays readable in the board's 3D view.
 *   <li><b>It is a redstone conductor</b> -- a full cube, so the default {@code isRedstoneConductor} test
 *       passes -- which means vanilla wire draws its connection to it. The port is visible in the world
 *       render, not only in the editor's overlay.
 *   <li><b>It is never a signal source,</b> so nothing placed in a tapped cell can conflict with the tap.
 *       A tap delivers the level <em>unchanged</em> to whatever reads it — it does not decay it — which is
 *       what makes a tapped cell behave as a source at a point rather than as a length of wire.
 *       The tap's cell is a source that the components <em>around</em> it read; its own contents are
 *       bypassed, and glass having no signal of its own makes that unambiguous.
 * </ul>
 *
 * <p>Two block types, deliberately not a broader list. An earlier rule here had to enumerate the vanilla
 * redstone set, which is a maintenance and correctness risk with no benefit -- every part a player could
 * usefully bridge with is non-air, so it refused nothing useful. Naming two blocks is a rule that cannot
 * rot.
 *
 * <p>Deliberately level-free, so the editor and the server judge a cell identically and cannot disagree
 * about what is offerable. Vanilla's own redstone test, {@code isRedstoneConductor}, resolves to
 * {@code isCollisionShapeFullBlock(level, pos)} and needs a level the client does not have for the board
 * dimension. NeoForge's {@code canRedstoneConnectTo} is a loader-only extension with no Fabric
 * equivalent, so relying on it would make the two loaders behave differently.
 */
public final class TapCell {
    private TapCell() {}

    /** Whether the block in {@code state} may carry a tap. */
    public static boolean isTap(BlockState state) {
        return state != null && (state.is(Blocks.GLASS) || state.is(Blocks.TINTED_GLASS));
    }

    /** Player-facing name for the rule, reused by the editor's refusal message. */
    public static String requirement() {
        return "a tap must be on glass or tinted glass";
    }
}
