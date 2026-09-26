package com.retiredroca.redstonepcbs.chip;

import java.util.ArrayList;
import java.util.List;

/**
 * The port options a cell offers: which side can be bridged, and in which direction, in the order the
 * editor cycles through them.
 *
 * <p>The ordering only ever changes which option a player lands on first. Every offered pair is
 * selectable, because deciding for the player that a given side cannot carry a signal would mean
 * encoding vanilla's per-block power rules here, and a wrong one of those silently refuses a working
 * build. The part's role is used to put the natural direction first, not to exclude the other.
 */
public final class PortOptions {
    private PortOptions() {}

    /**
     * The sides this cell may bridge.
     *
     * <p>{@code connectedSides} is the cell's real signal sides when the block has them — a wire's four
     * {@code RedstoneSide} properties — and {@code null} otherwise. A wire is horizontal in vanilla, so
     * without the real state it would be offered sides it cannot possibly reach.
     */
    public static Dir[] sides(Part part, Dir[] connectedSides) {
        if (connectedSides != null) {
            return connectedSides.clone();
        }
        Dir[] order = part.facingOrder();
        return order.length == 0 ? Dir.VALUES.clone() : order.clone();
    }

    /**
     * The sides a part can present, ignoring which one it currently faces. The candidate set comes from
     * the part's facing <em>family</em>, not its specific facing, so the facing itself is not needed here.
     */
    public static Dir[] candidateSides(Part part) {
        return sides(part, null);
    }

    /**
     * The direction a part naturally carries. A pure source pushes out, a pure sink takes in, and
     * anything else — a wire above all — defaults to in, with the other direction still offered.
     */
    public static PortFlow naturalFlow(Part part) {
        boolean source = part.has(Part.Role.SOURCE) || part.has(Part.Role.STRONG_EMITTER);
        boolean sink = part.has(Part.Role.SINK);
        if (source && !sink) {
            return PortFlow.OUT;
        }
        return PortFlow.IN;
    }

    /** The full ordered option list: each side twice, natural direction first. */
    public static List<PortLink> options(Part part, Dir[] connectedSides) {
        List<PortLink> out = new ArrayList<>();
        PortFlow natural = naturalFlow(part);
        PortFlow other = natural == PortFlow.IN ? PortFlow.OUT : PortFlow.IN;
        for (Dir side : sides(part, connectedSides)) {
            out.add(new PortLink(Dir.UP, side, natural));
            out.add(new PortLink(Dir.UP, side, other));
        }
        return out;
    }

    /**
     * Replaces the face on every option, since the editor cycles the face and side together. Options
     * are stored with a placeholder face.
     */
    public static List<PortLink> onFace(List<PortLink> options, Dir face) {
        List<PortLink> out = new ArrayList<>(options.size());
        for (PortLink option : options) {
            out.add(new PortLink(face, option.side(), option.flow()));
        }
        return out;
    }
}
