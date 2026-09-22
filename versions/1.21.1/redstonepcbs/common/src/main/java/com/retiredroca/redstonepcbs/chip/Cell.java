package com.retiredroca.redstonepcbs.chip;

/**
 * Mutable state of a single voxel. Field meaning depends on {@link #part}:
 *
 * <ul>
 *   <li>{@link #facing} - for a torch, the direction toward the block it is attached to; for a
 *       repeater/comparator, the output direction.</li>
 *   <li>{@link #power} - dust signal strength (0-15) or a comparator's output strength.</li>
 *   <li>{@link #delay} - repeater delay setting (0-3, meaning 1-4 redstone ticks).</li>
 *   <li>{@link #powered} - torch lit, repeater/comparator output on, lamp lit, or a solid block is
 *       strongly powered.</li>
 *   <li>{@link #weak} - a solid block is weakly powered (by dust sitting on top of it).</li>
 *   <li>{@link #subtract} - comparator subtract mode.</li>
 *   <li>{@link #on} - lever/button active.</li>
 *   <li>{@link #locked} - repeater locked by a side signal.</li>
 * </ul>
 */
public final class Cell {
    public Part part = Part.AIR;
    public Dir facing = Dir.UP;
    public int power;
    public int delay;
    public boolean powered;
    public boolean weak;
    public boolean subtract;
    public boolean on;
    public boolean locked;
    /** Redstone dust horizontal connections: bit0 N, bit1 E, bit2 S, bit3 W. Default is a cross. */
    public int dustMask = 0x0F;

    long pendingAt = -1L;

    /** Cached signature of the block an observer watches; transient (not persisted). */
    public int watchSig = Integer.MIN_VALUE;

    public void reset() {
        part = Part.AIR;
        facing = Dir.UP;
        power = 0;
        delay = 0;
        powered = false;
        weak = false;
        subtract = false;
        on = false;
        locked = false;
        pendingAt = -1L;
        watchSig = Integer.MIN_VALUE;
        dustMask = 0x0F;
    }

    public void copyFrom(Cell other) {
        part = other.part;
        facing = other.facing;
        power = other.power;
        delay = other.delay;
        powered = other.powered;
        weak = other.weak;
        subtract = other.subtract;
        on = other.on;
        locked = other.locked;
        dustMask = other.dustMask;
    }

    public boolean isEmpty() {
        return part == Part.AIR;
    }
}
