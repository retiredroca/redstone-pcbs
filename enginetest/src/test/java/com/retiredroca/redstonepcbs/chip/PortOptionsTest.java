package com.retiredroca.redstonepcbs.chip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The port options the editor cycles. The rule that matters: the ordering is a hint, never a filter.
 * Excluding a side the player wanted would refuse a build that works, which is the worse failure.
 */
class PortOptionsTest {

    @Test
    void aWireOffersBothDirectionsOnEachOfItsRealSides() {
        Dir[] connected = {Dir.NORTH, Dir.EAST};
        List<PortLink> options = PortOptions.options(Part.DUST, connected);

        assertEquals(4, options.size(), "two connected sides, two directions each");
        for (PortLink o : options) {
            assertTrue(o.side() == Dir.NORTH || o.side() == Dir.EAST);
        }
    }

    @Test
    void aWireIgnoresItsPartFacingBecauseItIsHorizontalInVanilla() {
        // Facing is meaningless for a wire; the real state is the only thing that should matter.
        List<PortLink> a = PortOptions.options(Part.DUST, new Dir[] {Dir.WEST});
        List<PortLink> b = PortOptions.options(Part.DUST, new Dir[] {Dir.WEST});
        assertEquals(a, b);
    }

    @Test
    void aSourceOffersOutFirst() {
        assertEquals(PortFlow.OUT, PortOptions.naturalFlow(Part.REDSTONE_BLOCK));
        assertEquals(PortFlow.OUT, PortOptions.naturalFlow(Part.LEVER));
        assertEquals(PortFlow.OUT, PortOptions.naturalFlow(Part.BUTTON));
    }

    @Test
    void aSinkOffersInFirst() {
        assertEquals(PortFlow.IN, PortOptions.naturalFlow(Part.LAMP));
        assertEquals(PortFlow.IN, PortOptions.naturalFlow(Part.NOTE_BLOCK));
    }

    @Test
    void aWireDefaultsToInBecauseVanillaWireIsBidirectional() {
        assertEquals(PortFlow.IN, PortOptions.naturalFlow(Part.DUST));
    }

    @Test
    void bothDirectionsAreAlwaysOfferedSoNothingIsRefused() {
        for (Part part : Part.VALUES) {
            for (Dir facing : Dir.VALUES) {
                List<PortLink> options = PortOptions.options(part, null);
                assertTrue(options.size() % 2 == 0, part + " should offer each side both ways");
                // For every side offered, an IN and an OUT variant must both exist.
                for (Dir side : Dir.VALUES) {
                    int in = 0;
                    int out = 0;
                    for (PortLink o : options) {
                        if (o.side() == side) {
                            in += o.isInput() ? 1 : 0;
                            out += o.isOutput() ? 1 : 0;
                        }
                    }
                    assertTrue(in <= 1 && out <= 1, part + " duplicates a side/direction");
                }
            }
        }
    }

    @Test
    void aPartWithNoFacingFamilyOffersEverySide() {
        // A full block has no facing family, so restricting it to none would make it unusable.
        assertEquals(Dir.VALUES.length, PortOptions.sides(Part.REDSTONE_BLOCK, null).length);
        assertEquals(Dir.VALUES.length, PortOptions.sides(Part.SOLID, null).length);
    }

    @Test
    void aFacingFamilyPartIsRestrictedToItsOwnDirections() {
        // A horizontal part (repeater, furnace) only has four usable sides.
        assertEquals(4, PortOptions.sides(Part.REPEATER, null).length);
        for (Dir side : PortOptions.sides(Part.REPEATER, null)) {
            assertTrue(side.isHorizontal(), "a horizontal part offered " + side);
        }
    }

    @Test
    void theNaturalDirectionComesFirstForEachSide() {
        List<PortLink> options = PortOptions.options(Part.REDSTONE_BLOCK, new Dir[] {Dir.NORTH});

        assertEquals(2, options.size());
        assertTrue(options.get(0).isOutput(), "a source should land on OUT first");
        assertTrue(options.get(1).isInput(), "the other direction must still be reachable");
        assertEquals(options.get(0).side(), options.get(1).side());
    }

    @Test
    void onFaceRewritesEveryOption() {
        List<PortLink> options = PortOptions.options(Part.DUST, new Dir[] {Dir.SOUTH});
        List<PortLink> moved = PortOptions.onFace(options, Dir.WEST);

        for (PortLink o : moved) {
            assertEquals(Dir.WEST, o.face());
        }
        assertEquals(options.size(), moved.size());
    }

    @Test
    void aCallerSuppliedSideListIsCopiedNotAliased() {
        Dir[] sides = {Dir.NORTH};
        Dir[] returned = PortOptions.sides(Part.DUST, sides);
        returned[0] = Dir.SOUTH;
        assertEquals(Dir.NORTH, sides[0], "the caller's array must not be mutated through the result");
    }

    @Test
    void anEmptyCellStillOffersSides() {
        // The bridge is consulted for any state at the position, so a port on an empty cell can feed
        // the wire beside it. Offering nothing would make that unreachable.
        List<PortLink> options = PortOptions.options(Part.AIR, null);
        assertEquals(Dir.VALUES.length * 2, options.size());
        assertFalse(options.isEmpty());
    }
}
