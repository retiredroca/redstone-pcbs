package com.retiredroca.redstonepcbs.chip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ChipWorldTest {

    private static void place(ChipWorld w, int x, int y, int z, Part part, Dir facing) {
        w.set(x, y, z, part, facing);
    }

    @Test
    void dustDecaysAwayFromASource() {
        ChipWorld w = new ChipWorld();
        place(w, 1, 0, 0, Part.REDSTONE_BLOCK, Dir.UP);
        place(w, 2, 0, 0, Part.DUST, Dir.UP);
        place(w, 3, 0, 0, Part.DUST, Dir.UP);
        place(w, 4, 0, 0, Part.DUST, Dir.UP);

        w.settleNow();

        assertEquals(15, w.cell(2, 0, 0).power);
        assertEquals(14, w.cell(3, 0, 0).power);
        assertEquals(13, w.cell(4, 0, 0).power);
    }

    @Test
    void torchInvertsItsSupport() {
        ChipWorld w = new ChipWorld();
        place(w, 1, 0, 0, Part.SOLID, Dir.UP);
        place(w, 1, 1, 0, Part.TORCH, Dir.DOWN);

        w.settleNow();
        assertTrue(w.cell(1, 1, 0).powered, "torch should be lit while its support is unpowered");

        place(w, 0, 0, 0, Part.REDSTONE_BLOCK, Dir.UP);
        w.settleNow();
        w.tick();

        assertFalse(w.cell(1, 1, 0).powered, "torch should turn off once its support is powered");
    }

    @Test
    void repeaterDelaysThenEmits() {
        ChipWorld w = new ChipWorld();
        place(w, 0, 0, 0, Part.REDSTONE_BLOCK, Dir.UP);
        place(w, 1, 0, 0, Part.REPEATER, Dir.EAST);
        place(w, 2, 0, 0, Part.LAMP, Dir.UP);

        w.settleNow();
        assertFalse(w.cell(2, 0, 0).powered, "lamp should be off before the repeater fires");

        w.tick();
        assertTrue(w.cell(1, 0, 0).powered, "repeater should be powered after its delay");
        assertTrue(w.cell(2, 0, 0).powered, "lamp should light after the repeater fires");
    }

    @Test
    void repeaterRespectsConfiguredDelay() {
        ChipWorld w = new ChipWorld();
        place(w, 0, 0, 0, Part.REDSTONE_BLOCK, Dir.UP);
        place(w, 1, 0, 0, Part.REPEATER, Dir.EAST);
        w.cell(1, 0, 0).delay = 3;

        w.settleNow();
        w.tick();
        w.tick();
        w.tick();
        assertFalse(w.cell(1, 0, 0).powered, "configured for 4 ticks, should not be on at tick 3");

        w.tick();
        assertTrue(w.cell(1, 0, 0).powered, "should be on at tick 4");
    }

    @Test
    void comparatorSubtractMode() {
        ChipWorld w = new ChipWorld();
        place(w, 0, 0, 0, Part.REDSTONE_BLOCK, Dir.UP);
        place(w, 1, 0, 0, Part.COMPARATOR, Dir.EAST);
        place(w, 1, 0, 1, Part.REDSTONE_BLOCK, Dir.UP);
        w.cell(1, 0, 0).subtract = true;

        w.settleNow();
        w.tick();

        assertEquals(0, w.cell(1, 0, 0).power, "15 back - 15 side should output 0");
    }

    @Test
    void comparatorCompareMode() {
        ChipWorld w = new ChipWorld();
        place(w, 0, 0, 0, Part.REDSTONE_BLOCK, Dir.UP);
        place(w, 1, 0, 0, Part.COMPARATOR, Dir.EAST);

        w.settleNow();
        w.tick();

        assertEquals(15, w.cell(1, 0, 0).power);
        assertTrue(w.cell(1, 0, 0).powered);
    }

    @Test
    void faceInputPowersBoundaryDust() {
        ChipWorld w = new ChipWorld();
        w.setFaceInput(Dir.EAST, 15);
        place(w, 15, 0, 0, Part.DUST, Dir.UP);

        w.settleNow();

        assertEquals(15, w.cell(15, 0, 0).power);
    }

    @Test
    void faceOutputReportsStrongestEmitter() {
        ChipWorld w = new ChipWorld();
        place(w, 15, 0, 0, Part.REDSTONE_BLOCK, Dir.UP);

        w.settleNow();

        assertEquals(15, w.getFaceOutput(Dir.EAST));
        assertEquals(0, w.getFaceOutput(Dir.WEST));
    }

    @Test
    void buttonPulsesThenReleases() {
        ChipWorld w = new ChipWorld();
        place(w, 1, 0, 0, Part.BUTTON, Dir.UP);
        place(w, 2, 0, 0, Part.LAMP, Dir.UP);

        w.pressButton(w.index(1, 0, 0));
        w.settleNow();
        assertTrue(w.cell(2, 0, 0).powered, "lamp should light while the button is pressed");

        for (int i = 0; i < 10; i++) {
            w.tick();
        }

        assertFalse(w.cell(1, 0, 0).on, "button should release after its pulse");
        assertFalse(w.cell(2, 0, 0).powered, "lamp should turn off after the pulse");
    }

    @Test
    void serializationRoundTrips() {
        ChipWorld w = new ChipWorld();
        w.set(1, 2, 3, Part.REPEATER, Dir.NORTH);
        w.cell(1, 2, 3).delay = 2;
        w.cell(1, 2, 3).powered = true;
        w.set(4, 5, 6, Part.DUST, Dir.UP);
        w.cell(4, 5, 6).power = 7;
        w.set(7, 8, 9, Part.TORCH, Dir.DOWN);
        w.cell(7, 8, 9).powered = true;
        w.set(10, 11, 12, Part.LEVER, Dir.UP);
        w.cell(10, 11, 12).on = true;

        ChipWorld r = ChipSerializer.read(ChipSerializer.write(w));

        assertEquals(Part.REPEATER, r.cell(1, 2, 3).part);
        assertEquals(Dir.NORTH, r.cell(1, 2, 3).facing);
        assertEquals(2, r.cell(1, 2, 3).delay);
        assertTrue(r.cell(1, 2, 3).powered);
        assertEquals(7, r.cell(4, 5, 6).power);
        assertEquals(Part.TORCH, r.cell(7, 8, 9).part);
        assertTrue(r.cell(7, 8, 9).powered);
        assertTrue(r.cell(10, 11, 12).on);
    }

    @Test
    void comparatorReadsAnalogFaceInput() {
        ChipWorld w = new ChipWorld();
        w.setFaceAnalog(Dir.EAST, 7);
        place(w, 15, 0, 0, Part.COMPARATOR, Dir.WEST);

        w.settleNow();
        w.tick();

        assertEquals(7, w.cell(15, 0, 0).power);
        assertTrue(w.cell(15, 0, 0).powered);
    }

    @Test
    void analogFaceInputDoesNotPowerDust() {
        ChipWorld w = new ChipWorld();
        w.setFaceAnalog(Dir.EAST, 15);
        place(w, 15, 0, 0, Part.DUST, Dir.UP);

        w.settleNow();

        assertEquals(0, w.cell(15, 0, 0).power);
    }

    @Test
    void torchRotatesOnlyBetweenSupportedDirections() {
        ChipWorld w = new ChipWorld();
        place(w, 5, 3, 5, Part.SOLID, Dir.UP);
        place(w, 5, 4, 4, Part.SOLID, Dir.UP);
        place(w, 5, 4, 5, Part.TORCH, Dir.DOWN);

        int idx = w.index(5, 4, 5);
        w.rotate(idx);

        assertEquals(Dir.NORTH, w.cell(5, 4, 5).facing, "should move to the supported north side");
    }

    @Test
    void dustAutoShapesFromNeighbours() {
        ChipWorld w = new ChipWorld();
        // Isolated wire is a dot.
        place(w, 5, 5, 5, Part.DUST, Dir.UP);
        assertEquals(0, w.cell(5, 5, 5).dustMask, "an isolated wire is a dot");
        assertEquals(0, w.cell(5, 5, 5).dustUpMask);

        // Beside a source it points at it (EAST bit).
        place(w, 6, 5, 5, Part.REDSTONE_BLOCK, Dir.UP);
        assertEquals(2, w.cell(5, 5, 5).dustMask, "wire points at the adjacent source");

        // Two wires connect to each other.
        place(w, 4, 5, 5, Part.DUST, Dir.UP);
        assertEquals(2 | 8, w.cell(5, 5, 5).dustMask, "wire connects east and west");
        assertEquals(2, w.cell(4, 5, 5).dustMask, "the other wire connects east");

        // Removing a neighbour clears the side again.
        w.clear(4, 5, 5);
        assertEquals(2, w.cell(5, 5, 5).dustMask, "removed neighbour clears the west side");

        // An L: source south, wire north.
        ChipWorld l = new ChipWorld();
        place(l, 5, 5, 5, Part.DUST, Dir.UP);
        place(l, 5, 5, 6, Part.DUST, Dir.UP);
        place(l, 5, 5, 7, Part.REDSTONE_BLOCK, Dir.UP);
        assertEquals(4, l.cell(5, 5, 5).dustMask, "corner points south");
        assertEquals(1 | 4, l.cell(5, 5, 6).dustMask, "middle wire points north and south");

        // A cross when wires are on all four sides.
        ChipWorld c = new ChipWorld();
        place(c, 5, 5, 5, Part.DUST, Dir.UP);
        place(c, 4, 5, 5, Part.DUST, Dir.UP);
        place(c, 6, 5, 5, Part.DUST, Dir.UP);
        place(c, 5, 5, 4, Part.DUST, Dir.UP);
        place(c, 5, 5, 6, Part.DUST, Dir.UP);
        assertEquals(0x0F, c.cell(5, 5, 5).dustMask, "wires on all sides form a cross");
    }

    @Test
    void dustClimbsUpTheSideOfABlock() {
        ChipWorld w = new ChipWorld();
        place(w, 1, 0, 5, Part.DUST, Dir.UP);
        place(w, 2, 0, 5, Part.SOLID, Dir.UP);
        place(w, 2, 1, 5, Part.DUST, Dir.UP);
        place(w, 3, 1, 5, Part.REDSTONE_BLOCK, Dir.UP);

        w.settleNow();

        // The upper wire is powered by the source, and the lower one climbs up to it (EAST = UP).
        assertEquals(15, w.cell(2, 1, 5).power);
        assertEquals(2, w.cell(1, 0, 5).dustUpMask, "the lower wire climbs east");
        assertEquals(14, w.cell(1, 0, 5).power, "power climbs the block with one step of decay");
    }

    @Test
    void dustDescendsToAWireBelowAnAdjacentBlock() {
        ChipWorld w = new ChipWorld();
        place(w, 2, 1, 5, Part.DUST, Dir.UP);
        place(w, 3, 0, 5, Part.DUST, Dir.UP);
        place(w, 4, 0, 5, Part.REDSTONE_BLOCK, Dir.UP);

        w.settleNow();

        // (3,1,5) is air, so the upper wire reads the wire one block below it to the east.
        assertEquals(2, w.cell(2, 1, 5).dustMask, "the upper wire connects east");
        assertEquals(14, w.cell(2, 1, 5).power, "power descends with one step of decay");
    }

    @Test
    void dustDotWeaklyPowersTheBlockBelow() {
        ChipWorld w = new ChipWorld();
        place(w, 1, 0, 0, Part.SOLID, Dir.UP);
        place(w, 1, 1, 0, Part.DUST, Dir.UP);
        place(w, 0, 1, 0, Part.REDSTONE_BLOCK, Dir.UP);

        w.settleNow();

        assertEquals(15, w.cell(1, 1, 0).power);
        assertTrue(w.cell(1, 0, 0).weak, "a dot still weakly powers the block under it");
    }

    @Test
    void hopperRotatesOnlyThroughValidDirections() {
        ChipWorld w = new ChipWorld();
        place(w, 4, 4, 4, Part.HOPPER, Dir.DOWN);
        int idx = w.index(4, 4, 4);

        // A hopper can face down or a side, never up.
        for (int i = 0; i < 10; i++) {
            w.rotate(idx);
            assertNotEquals(Dir.UP, w.cell(4, 4, 4).facing, "hoppers must never face up");
        }
        assertEquals(Dir.DOWN, w.cell(4, 4, 4).facing, "cycles back around after 5 steps");
    }

    @Test
    void sanitizeFacingClampsToSupportedDirections() {
        assertEquals(Dir.DOWN, Part.HOPPER.sanitizeFacing(Dir.UP));
        assertEquals(Dir.DOWN, Part.TORCH.sanitizeFacing(Dir.UP));
        assertEquals(Dir.NORTH, Part.REPEATER.sanitizeFacing(Dir.UP));
        assertEquals(Dir.EAST, Part.HOPPER.sanitizeFacing(Dir.EAST));
        assertEquals(Dir.SOUTH, Part.REPEATER.sanitizeFacing(Dir.SOUTH));
    }

    @Test
    void torchStandsOnTheBoardFloor() {
        ChipWorld w = new ChipWorld();
        place(w, 2, 0, 2, Part.TORCH, Dir.DOWN);

        assertTrue(w.hasSupport(w.index(2, 0, 2), Dir.DOWN), "the bottom layer is a floor");
        assertFalse(w.hasSupport(w.index(2, 1, 2), Dir.DOWN), "above the floor there is no support");
    }

    @Test
    void comparatorReadsContainerAnalog() {
        ChipWorld w = new ChipWorld();
        place(w, 0, 0, 0, Part.FURNACE, Dir.EAST);
        place(w, 1, 0, 0, Part.COMPARATOR, Dir.EAST);
        w.setCellAnalog(w.index(0, 0, 0), 9);

        w.settleNow();
        w.tick();

        assertEquals(9, w.cell(1, 0, 0).power, "comparator reads the container behind it");
        assertTrue(w.cell(1, 0, 0).powered);
    }

    @Test
    void containerAnalogDoesNotPowerDust() {
        ChipWorld w = new ChipWorld();
        place(w, 0, 0, 0, Part.FURNACE, Dir.EAST);
        place(w, 1, 0, 0, Part.DUST, Dir.UP);
        w.setCellAnalog(w.index(0, 0, 0), 15);

        w.settleNow();

        assertEquals(0, w.cell(1, 0, 0).power, "an analog container signal never feeds dust");
    }

    @Test
    void serializationRoundTripsContainerPartAndAnalog() {
        ChipWorld w = new ChipWorld();
        // CRAFTER has an ordinal above 15, proving the widened part field.
        w.set(2, 3, 4, Part.CRAFTER, Dir.WEST);
        w.setCellAnalog(w.index(2, 3, 4), 11);

        ChipWorld r = ChipSerializer.read(ChipSerializer.write(w));

        assertEquals(Part.CRAFTER, r.cell(2, 3, 4).part);
        assertEquals(Dir.WEST, r.cell(2, 3, 4).facing);
        assertEquals(11, r.cell(2, 3, 4).analog);
    }

    @Test
    void observerRotatesThroughAllSixDirections() {
        ChipWorld w = new ChipWorld();
        place(w, 4, 4, 4, Part.OBSERVER, Dir.NORTH);
        int idx = w.index(4, 4, 4);

        boolean sawUp = false;
        boolean sawDown = false;
        for (int i = 0; i < 6; i++) {
            w.rotate(idx);
            sawUp |= w.cell(4, 4, 4).facing == Dir.UP;
            sawDown |= w.cell(4, 4, 4).facing == Dir.DOWN;
        }
        assertTrue(sawUp && sawDown, "observers face all six directions");
    }

    @Test
    void pulseLayerTogglesLeversAndPressesButtonsOnThatLayerOnly() {
        ChipWorld w = new ChipWorld();
        place(w, 0, 2, 0, Part.LEVER, Dir.UP);
        place(w, 1, 2, 0, Part.BUTTON, Dir.UP);
        place(w, 0, 3, 0, Part.LEVER, Dir.UP);

        w.pulseLayer(2);
        assertTrue(w.cell(0, 2, 0).on, "lever on the pulsed layer turns on");
        assertTrue(w.cell(1, 2, 0).on, "button on the pulsed layer is pressed");
        assertFalse(w.cell(0, 3, 0).on, "levers on other layers are untouched");

        w.pulseLayer(2);
        assertFalse(w.cell(0, 2, 0).on, "a second pulse toggles the lever back");
    }

    @Test
    void facingFamiliesGroupCorrectly() {
        assertTrue(Part.LEVER.isFaceAttached());
        assertTrue(Part.BUTTON.isFaceAttached());
        assertFalse(Part.LEVER.isHorizontalOnly());
        assertEquals(Dir.UP, Part.LEVER.sanitizeFacing(Dir.UP));
        assertEquals(Dir.DOWN, Part.BUTTON.sanitizeFacing(Dir.DOWN));

        assertTrue(Part.OBSERVER.isSixWay());
        assertTrue(Part.CRAFTER.isSixWay());
        assertTrue(Part.REPEATER.isHorizontalOnly());
        assertTrue(Part.FURNACE.isHorizontalOnly());

        assertTrue(Part.SOLID.isConductive());
        assertTrue(Part.HOPPER.isConductive());
        assertFalse(Part.GLASS.isConductive());
        assertTrue(Part.GLASS.isFullBlock());

        assertEquals(Part.ContainerFamily.COOKER, Part.FURNACE.containerFamily());
        assertEquals(Part.ContainerFamily.COOKER, Part.BLAST_FURNACE.containerFamily());
        assertEquals(Part.ContainerFamily.COOKER, Part.SMOKER.containerFamily());
        assertEquals(Part.ContainerFamily.HOPPER, Part.HOPPER.containerFamily());
        assertEquals(Part.ContainerFamily.NONE, Part.COMPARATOR.containerFamily());
        assertTrue(Part.CRAFTER.isContainer());
        assertFalse(Part.NOTE_BLOCK.isContainer());
    }
}
