package com.retiredroca.redstonepcbs.chip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The two-tap model, and specifically the bug it exists to fix: assigning one direction used to
 * overwrite the other, so a board could not take a signal and also drive one.
 */
class BoardTapsTest {

    @Test
    @DisplayName("an input and an output coexist on different cells")
    void holdsBothDirections() {
        BoardTaps taps = new BoardTaps(10, 20);
        assertTrue(taps.hasIn());
        assertTrue(taps.hasOut());
        assertFalse(taps.isEmpty());
        assertEquals(10, taps.cellOf(PortFlow.IN));
        assertEquals(20, taps.cellOf(PortFlow.OUT));
    }

    @Test
    @DisplayName("setting OUT must not disturb an IN elsewhere -- the reported bug")
    void settingOutLeavesInAlone() {
        BoardTaps withIn = new BoardTaps(10, BoardTaps.NONE);
        BoardTaps both = withIn.with(PortFlow.OUT, 20);
        assertEquals(10, both.cellOf(PortFlow.IN), "the input was overwritten");
        assertEquals(20, both.cellOf(PortFlow.OUT));
    }

    @Test
    @DisplayName("setting IN must not disturb an OUT elsewhere -- the same bug, mirrored")
    void settingInLeavesOutAlone() {
        BoardTaps withOut = BoardTaps.of(PortFlow.OUT, 20);
        BoardTaps both = withOut.with(PortFlow.IN, 10);
        assertEquals(20, both.cellOf(PortFlow.OUT), "the output was overwritten");
        assertEquals(10, both.cellOf(PortFlow.IN));
    }

    @Test
    @DisplayName("a cell cannot carry both directions")
    void refusesBothOnOneCell() {
        assertThrows(IllegalArgumentException.class, () -> new BoardTaps(7, 7));
    }

    @Test
    @DisplayName("re-pointing a direction moves it, rather than adding a second")
    void assigningMovesTheSameDirection() {
        BoardTaps one = BoardTaps.of(PortFlow.IN, 10);
        BoardTaps moved = one.with(PortFlow.IN, 33);
        assertEquals(33, moved.cellOf(PortFlow.IN));
        assertEquals(1, countTaps(moved), "the old input should be gone, not kept alongside");
    }

    @Test
    @DisplayName("switching a tapped cell to the other direction releases the first")
    void switchingDirectionOnSameCellReleases() {
        BoardTaps tapped = BoardTaps.of(PortFlow.IN, 10);
        BoardTaps flipped = tapped.with(PortFlow.OUT, 10);
        assertFalse(flipped.hasIn(), "the input on the same cell should have been released");
        assertEquals(10, flipped.cellOf(PortFlow.OUT));
    }

    @Test
    @DisplayName("clearing one cell leaves the other direction untouched")
    void clearingOneLeavesOther() {
        BoardTaps both = new BoardTaps(10, 20);
        BoardTaps after = both.cleared(10);
        assertFalse(after.hasIn());
        assertEquals(20, after.cellOf(PortFlow.OUT));
    }

    @Test
    @DisplayName("clearing a cell that holds nothing changes nothing")
    void clearingUntappedIsIdentity() {
        BoardTaps both = new BoardTaps(10, 20);
        assertSame(both, both.cleared(99));
    }

    @Test
    @DisplayName("NONE clears a direction")
    void noneClears() {
        BoardTaps both = new BoardTaps(10, 20);
        BoardTaps after = both.with(PortFlow.IN, BoardTaps.NONE);
        assertFalse(after.hasIn());
        assertEquals(20, after.cellOf(PortFlow.OUT));
        assertTrue(after.with(PortFlow.OUT, BoardTaps.NONE).isEmpty());
    }

    @Test
    @DisplayName("a cell reports the direction it is tapped in, or none")
    void flowAtIsReadOnly() {
        BoardTaps both = new BoardTaps(10, 20);
        assertEquals(PortFlow.IN, both.flowAt(10));
        assertEquals(PortFlow.OUT, both.flowAt(20));
        assertNull(both.flowAt(30));
        assertNull(both.flowAt(BoardTaps.NONE));
    }

    @Test
    @DisplayName("the editor's per-cell cycle walks in, out, clear without losing the other")
    void perCellCycleKeepsBoth() {
        // Untapped cell -> IN -> OUT -> cleared, which is what one key press does.
        BoardTaps start = BoardTaps.of(PortFlow.IN, 10);
        BoardTaps afterSecondPress = start.with(PortFlow.OUT, 10);
        assertEquals(PortFlow.OUT, afterSecondPress.flowAt(10));
        BoardTaps afterThirdPress = afterSecondPress.cleared(10);
        assertTrue(afterThirdPress.isEmpty());
    }

    @Test
    @DisplayName("a full round trip leaves the board exactly as it started")
    void cycleIsReversible() {
        BoardTaps start = new BoardTaps(10, 20);
        BoardTaps round = start.with(PortFlow.IN, 10).with(PortFlow.OUT, 20);
        assertEquals(start, round);
    }

    private static int countTaps(BoardTaps taps) {
        return (taps.hasIn() ? 1 : 0) + (taps.hasOut() ? 1 : 0);
    }
}
