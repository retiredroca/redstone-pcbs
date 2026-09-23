package com.retiredroca.redstonepcbs.chip;

import java.util.TreeSet;

/**
 * A fixed-size voxel grid that simulates a subset of Minecraft redstone with vanilla-like timings.
 *
 * <p>The simulation is deterministic: delayed parts (torches, repeaters, comparators, buttons) are
 * driven by a scheduled-tick queue ordered by (due tick, cell index), while dust and solid blocks
 * settle to a fixpoint within each redstone tick. There is no quasi-connectivity and no
 * update-order dependence, so the same input state always produces the same result.
 *
 * <p>Each of the six outer planes of the grid corresponds to a world face. Outside signals enter
 * through {@link #setFaceInput} and the strongest outward signal per face is read back through
 * {@link #getFaceOutput}.
 *
 * <p>This class is pure Java (no Minecraft imports) so it can be unit-tested on its own.
 */
public final class ChipWorld {
    public static final int SIZE = 16;
    public static final int FORMAT_VERSION = 5;

    private static final int INDEX_BITS = 12;
    private static final int INDEX_MASK = (1 << INDEX_BITS) - 1;
    private static final int MAX_SETTLE_ITERATIONS = 64;
    private static final int TORCH_DELAY = 1;
    private static final int COMPARATOR_DELAY = 1;
    private static final int BUTTON_PULSE_TICKS = 10;

    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final int cellCount;
    private final Cell[] cells;
    private final int[] faceInput = new int[Dir.VALUES.length];
    private final int[] faceAnalog = new int[Dir.VALUES.length];
    private final TreeSet<Long> schedule = new TreeSet<>();

    private long tick;
    private boolean dirty = true;
    private boolean filterHopperMode = true;

    /** Filter mode: a hopper holds one filter item and only passes matching items through. */
    public boolean isFilterHopperMode() {
        return filterHopperMode;
    }

    public void setFilterHopperMode(boolean filter) {
        if (filterHopperMode != filter) {
            filterHopperMode = filter;
            markDirty();
        }
    }

    public void toggleHopperMode() {
        filterHopperMode = !filterHopperMode;
        markDirty();
    }

    public ChipWorld() {
        this(SIZE, SIZE, SIZE);
    }

    public ChipWorld(int sizeX, int sizeY, int sizeZ) {
        if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) {
            throw new IllegalArgumentException("grid dimensions must be positive");
        }
        if (sizeX * sizeY * sizeZ > INDEX_MASK + 1) {
            throw new IllegalArgumentException("grid too large for index encoding");
        }
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.cellCount = sizeX * sizeY * sizeZ;
        this.cells = new Cell[cellCount];
        for (int i = 0; i < cellCount; i++) {
            cells[i] = new Cell();
        }
    }

    // --- geometry ---------------------------------------------------------------------------------

    public int sizeX() {
        return sizeX;
    }

    public int sizeY() {
        return sizeY;
    }

    public int sizeZ() {
        return sizeZ;
    }

    public int cellCount() {
        return cellCount;
    }

    public long currentTick() {
        return tick;
    }

    public boolean inBounds(int x, int y, int z) {
        return x >= 0 && x < sizeX && y >= 0 && y < sizeY && z >= 0 && z < sizeZ;
    }

    public int index(int x, int y, int z) {
        return (y * sizeZ + z) * sizeX + x;
    }

    public int xOf(int index) {
        return index % sizeX;
    }

    public int yOf(int index) {
        return (index / (sizeX * sizeZ));
    }

    public int zOf(int index) {
        return (index / sizeX) % sizeZ;
    }

    /** Index of the neighbour of {@code index} in {@code dir}, or -1 if it leaves the grid. */
    private int neighbour(int index, Dir dir) {
        int x = xOf(index) + dir.dx;
        int y = yOf(index) + dir.dy;
        int z = zOf(index) + dir.dz;
        if (!inBounds(x, y, z)) {
            return -1;
        }
        return index(x, y, z);
    }

    public Cell cell(int index) {
        return cells[index];
    }

    public Cell cell(int x, int y, int z) {
        return cells[index(x, y, z)];
    }

    // --- mutation ---------------------------------------------------------------------------------

    public void markDirty() {
        dirty = true;
    }

    public void set(int x, int y, int z, Part part, Dir facing) {
        set(index(x, y, z), part, facing);
    }

    public void set(int index, Part part, Dir facing) {
        Cell c = cells[index];
        c.reset();
        c.part = part;
        c.facing = part.sanitizeFacing(facing);
        if (part == Part.TORCH) {
            // A freshly placed torch starts in its natural state rather than waiting a scheduled tick.
            c.powered = desiredTorchLit(index);
        }
        markDirty();
        refreshDustShapesAround(index);
    }

    public void clear(int x, int y, int z) {
        int index = index(x, y, z);
        cells[index].reset();
        markDirty();
        refreshDustShapesAround(index);
    }

    /** Rotates a directional part to the next direction in its facing family's order. */
    public void rotate(int index) {
        Cell c = cells[index];
        Dir[] order = c.part.facingOrder();
        if (order.length == 0) {
            return;
        }
        int start = 0;
        for (int i = 0; i < order.length; i++) {
            if (order[i] == c.facing) {
                start = i;
                break;
            }
        }
        if (c.part.needsSupport()) {
            // A torch only rotates to a direction that actually has a support.
            for (int k = 1; k <= order.length; k++) {
                Dir candidate = order[(start + k) % order.length];
                if (hasSupport(index, candidate)) {
                    c.facing = candidate;
                    markDirty();
                    refreshDustShapesAround(index);
                    return;
                }
            }
            return;
        }
        c.facing = order[(start + 1) % order.length];
        markDirty();
        refreshDustShapesAround(index);
    }

    /** True when the neighbour of {@code index} in {@code dir} can support a torch. The board's
     * bottom layer (y == 0) acts as a floor, so a torch can stand on it. */
    public boolean hasSupport(int index, Dir dir) {
        if (dir == Dir.DOWN && yOf(index) == 0) {
            return true;
        }
        int n = neighbour(index, dir);
        return n >= 0 && cells[n].part.isFullBlock();
    }

    public void cycleRepeaterDelay(int index) {
        Cell c = cells[index];
        if (c.part == Part.REPEATER) {
            c.delay = (c.delay + 1) & 3;
            markDirty();
        }
    }

    public void toggleComparatorMode(int index) {
        Cell c = cells[index];
        if (c.part == Part.COMPARATOR) {
            c.subtract = !c.subtract;
            markDirty();
        }
    }

    public void setLever(int index, boolean on) {
        Cell c = cells[index];
        if (c.part == Part.LEVER) {
            c.on = on;
            c.powered = on;
            markDirty();
        }
    }

    public void pressButton(int index) {
        Cell c = cells[index];
        if (c.part == Part.BUTTON) {
            c.on = true;
            markDirty();
        }
    }

    /** Toggles a lever, presses a button, or cycles a dust's shape. */
    public void interact(int index) {
        Cell c = cells[index];
        if (c.part == Part.LEVER) {
            setLever(index, !c.on);
        } else if (c.part == Part.BUTTON) {
            pressButton(index);
        } else if (c.part == Part.DUST && c.dustMask == 0 && c.dustUpMask == 0) {
            // Vanilla: a wire can only be shaped by hand when it is isolated; with any neighbour
            // it shapes itself automatically.
            cycleDustShape(index);
        }
    }

    /**
     * Test trigger: toggles every lever and presses every button on layer {@code y}, so a circuit
     * can be started from a chosen slice without touching each part individually.
     */
    public void pulseLayer(int y) {
        if (y < 0 || y >= sizeY) {
            return;
        }
        for (int x = 0; x < sizeX; x++) {
            for (int z = 0; z < sizeZ; z++) {
                int i = index(x, y, z);
                Cell c = cells[i];
                if (c.part == Part.LEVER) {
                    setLever(i, !c.on);
                } else if (c.part == Part.BUTTON) {
                    pressButton(i);
                }
            }
        }
    }

    /** Cycles dust through cross -> north-south -> east-west -> dot -> cross. */
    public void cycleDustShape(int index) {
        Cell c = cells[index];
        c.dustMask = switch (c.dustMask) {
            case 0x0F -> 0x05;
            case 0x05 -> 0x0A;
            case 0x0A -> 0x00;
            default -> 0x0F;
        };
        markDirty();
    }

    private static int bit(Dir d) {
        return switch (d) {
            case NORTH -> 1;
            case EAST -> 2;
            case SOUTH -> 4;
            case WEST -> 8;
            default -> 0;
        };
    }

    private static final int DUST_NONE = 0;
    private static final int DUST_SIDE = 1;
    private static final int DUST_UP = 2;

    /** True when the cell at {@code index} is a conductor (solid block or hopper). */
    private boolean isConductorAt(int index) {
        return index >= 0 && cells[index].part.isConductive();
    }

    /**
     * Whether a wire connects to its neighbour in {@code d} (mirrors vanilla
     * {@code RedStoneWireBlock.shouldConnectTo}): wires always connect, repeaters only along their
     * facing axis, observers only on the side they output, and other signal sources on any side.
     */
    private boolean dustConnectsTo(int index, Dir d) {
        int n = neighbour(index, d);
        if (n < 0) {
            return true;
        }
        Cell c = cells[n];
        return switch (c.part) {
            case DUST, TORCH, LEVER, BUTTON, REDSTONE_BLOCK, COMPARATOR -> true;
            case REPEATER -> d == c.facing || d == c.facing.opposite();
            case OBSERVER -> d == c.facing;
            default -> false;
        };
    }

    /** The side state of the wire at {@code index} toward {@code d}: NONE, SIDE or UP (climb). */
    private int dustSide(int index, Dir d) {
        int n = neighbour(index, d);
        if (n < 0) {
            // The board's face joins the world, so a boundary wire always points outward.
            return DUST_SIDE;
        }
        if (!isConductorAt(neighbour(index, Dir.UP)) && isConductorAt(n)) {
            int up = neighbour(n, Dir.UP);
            if (up >= 0 && cells[up].part == Part.DUST) {
                return DUST_UP;
            }
        }
        if (dustConnectsTo(index, d)) {
            return DUST_SIDE;
        }
        if (isConductorAt(n)) {
            return DUST_NONE;
        }
        int down = neighbour(n, Dir.DOWN);
        return down >= 0 && cells[down].part == Part.DUST ? DUST_SIDE : DUST_NONE;
    }

    /** Recomputes the shape of the wire at {@code index} from its neighbours. */
    private void refreshDustShape(int index) {
        Cell c = cells[index];
        if (c.part != Part.DUST) {
            return;
        }
        int sides = 0;
        int up = 0;
        for (Dir d : Part.HORIZONTAL_ORDER) {
            int s = dustSide(index, d);
            if (s == DUST_SIDE) {
                sides |= bit(d);
            } else if (s == DUST_UP) {
                up |= bit(d);
            }
        }
        if (c.dustMask != sides || c.dustUpMask != up) {
            c.dustMask = sides;
            c.dustUpMask = up;
            markDirty();
        }
    }

    /** Refreshes the wire at {@code index} and every wire within one block (3x3x3). */
    private void refreshDustShapesAround(int index) {
        int x = xOf(index);
        int y = yOf(index);
        int z = zOf(index);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (inBounds(x + dx, y + dy, z + dz)) {
                        refreshDustShape(index(x + dx, y + dy, z + dz));
                    }
                }
            }
        }
    }

    /** Re-derives every wire's shape (used after loading a board). */
    public void refreshAllDustShapes() {
        for (int i = 0; i < cellCount; i++) {
            refreshDustShape(i);
        }
    }

    /** Whether the wire at {@code index} emits toward {@code out} (vanilla {@code getSignal}). */
    private boolean dustEmitsTo(int index, Dir out) {
        if (out == Dir.UP) {
            return false;
        }
        if (out == Dir.DOWN) {
            return true;
        }
        int b = bit(out);
        return (cells[index].dustMask & b) != 0 || (cells[index].dustUpMask & b) != 0;
    }

    // --- face I/O ---------------------------------------------------------------------------------

    public int getFaceInput(Dir face) {
        return faceInput[face.ordinal()];
    }

    public void setFaceInput(Dir face, int power) {
        int clamped = Math.max(0, Math.min(15, power));
        if (faceInput[face.ordinal()] != clamped) {
            faceInput[face.ordinal()] = clamped;
            markDirty();
        }
    }

    /**
     * Analog signal (0-15) read from a container or other analog source on {@code face}, e.g. a
     * chest or hopper. Unlike {@link #getFaceInput} this only feeds comparators, never dust.
     */
    public int getFaceAnalog(Dir face) {
        return faceAnalog[face.ordinal()];
    }

    public void setFaceAnalog(Dir face, int power) {
        int clamped = Math.max(0, Math.min(15, power));
        if (faceAnalog[face.ordinal()] != clamped) {
            faceAnalog[face.ordinal()] = clamped;
            markDirty();
        }
    }

    /**
     * Sets the analog comparator output reported by a container/processor cell. Refreshed by the
     * block entity from the part's vanilla inventory/progress; only read by adjacent comparators.
     */
    public void setCellAnalog(int index, int power) {
        int clamped = Math.max(0, Math.min(15, power));
        if (cells[index].analog != clamped) {
            cells[index].analog = clamped;
            markDirty();
        }
    }

    /** Strongest signal this grid pushes out through {@code face}. */
    public int getFaceOutput(Dir face) {
        int best = 0;
        int plane = switch (face) {
            case UP -> sizeY - 1;
            case DOWN -> 0;
            case NORTH -> 0;
            case SOUTH -> sizeZ - 1;
            case WEST -> 0;
            case EAST -> sizeX - 1;
        };
        if (face == Dir.UP || face == Dir.DOWN) {
            for (int x = 0; x < sizeX; x++) {
                for (int z = 0; z < sizeZ; z++) {
                    best = Math.max(best, emitPower(index(x, plane, z), face));
                }
            }
        } else if (face == Dir.NORTH || face == Dir.SOUTH) {
            for (int x = 0; x < sizeX; x++) {
                for (int y = 0; y < sizeY; y++) {
                    best = Math.max(best, emitPower(index(x, y, plane), face));
                }
            }
        } else {
            for (int y = 0; y < sizeY; y++) {
                for (int z = 0; z < sizeZ; z++) {
                    best = Math.max(best, emitPower(index(plane, y, z), face));
                }
            }
        }
        return best;
    }

    // --- simulation -------------------------------------------------------------------------------

    public boolean isActive() {
        return dirty || !schedule.isEmpty();
    }

    public void settleNow() {
        settle();
        scheduleDelayed();
        dirty = false;
    }

    /** Advances the simulation by one redstone tick. */
    public void tick() {
        tick++;
        applyDueDelayed();
        updateObservers();
        settle();
        scheduleDelayed();
        dirty = false;
    }

    /** Observers pulse when the state of the cell they watch changes. */
    private void updateObservers() {
        for (int i = 0; i < cellCount; i++) {
            Cell c = cells[i];
            if (c.part != Part.OBSERVER) {
                continue;
            }
            int watched = neighbour(i, c.facing);
            int sig = watched < 0 ? Integer.MIN_VALUE : signature(watched);
            if (c.watchSig == Integer.MIN_VALUE) {
                c.watchSig = sig;
            } else if (sig != c.watchSig) {
                c.watchSig = sig;
                if (!c.powered) {
                    c.powered = true;
                    markDirty();
                }
            }
        }
    }

    private int signature(int index) {
        Cell c = cells[index];
        return c.part.ordinal()
                | (c.powered ? 1 << 8 : 0)
                | ((c.power & 0xF) << 9)
                | (c.on ? 1 << 13 : 0)
                | (c.subtract ? 1 << 14 : 0)
                | (c.facing.ordinal() << 15);
    }

    private void applyDueDelayed() {
        while (!schedule.isEmpty()) {
            long key = schedule.first();
            long at = key >>> INDEX_BITS;
            if (at > tick) {
                return;
            }
            schedule.pollFirst();
            int index = (int) (key & INDEX_MASK);
            Cell c = cells[index];
            if (c.pendingAt != at) {
                continue;
            }
            c.pendingAt = -1L;
            fireDelayed(index);
        }
    }

    private void fireDelayed(int index) {
        Cell c = cells[index];
        switch (c.part) {
            case TORCH -> c.powered = desiredTorchLit(index);
            case REPEATER -> c.powered = desiredRepeaterPowered(index);
            case COMPARATOR -> {
                int out = desiredComparatorOutput(index);
                c.power = out;
                c.powered = out > 0;
            }
            case BUTTON -> c.on = false;
            case OBSERVER -> c.powered = false;
            default -> {
            }
        }
    }

    private void scheduleDelayed() {
        for (int i = 0; i < cellCount; i++) {
            Cell c = cells[i];
            switch (c.part) {
                case TORCH -> {
                    boolean desired = desiredTorchLit(i);
                    if (desired != c.powered) {
                        pending(i, c, TORCH_DELAY);
                    } else {
                        cancel(c);
                    }
                }
                case REPEATER -> {
                    boolean desired = desiredRepeaterPowered(i);
                    if (desired != c.powered) {
                        pending(i, c, c.delay + 1);
                    } else {
                        cancel(c);
                    }
                }
                case COMPARATOR -> {
                    int out = desiredComparatorOutput(i);
                    if (out != c.power || (out > 0) != c.powered) {
                        pending(i, c, COMPARATOR_DELAY);
                    } else {
                        cancel(c);
                    }
                }
                case BUTTON -> {
                    if (c.on) {
                        pending(i, c, BUTTON_PULSE_TICKS);
                    } else {
                        cancel(c);
                    }
                }
                case OBSERVER -> {
                    if (c.powered) {
                        pending(i, c, 1);
                    } else {
                        cancel(c);
                    }
                }
                default -> {
                }
            }
        }
    }

    private void pending(int index, Cell c, int delayTicks) {
        if (c.pendingAt >= 0) {
            return;
        }
        long at = tick + Math.max(1, delayTicks);
        c.pendingAt = at;
        schedule.add((at << INDEX_BITS) | index);
    }

    private void cancel(Cell c) {
        c.pendingAt = -1L;
    }

    private void settle() {
        int iterations = 0;
        boolean changed;
        do {
            changed = false;
            changed |= settleSolids();
            changed |= settleDust();
            changed |= settleLamps();
        } while (changed && ++iterations < MAX_SETTLE_ITERATIONS);
    }

    private boolean settleSolids() {
        boolean changed = false;
        for (int i = 0; i < cellCount; i++) {
            Cell c = cells[i];
            if (!c.part.isConductive()) {
                continue;
            }
            boolean strong = isStronglyPowered(i);
            boolean weak = isWeaklyPowered(i);
            if (c.powered != strong || c.weak != weak) {
                c.powered = strong;
                c.weak = weak;
                changed = true;
            }
        }
        return changed;
    }

    private boolean settleDust() {
        boolean changed = false;
        for (int i = 0; i < cellCount; i++) {
            Cell c = cells[i];
            if (c.part != Part.DUST) {
                continue;
            }
            int power = computeDustPower(i);
            if (c.power != power) {
                c.power = power;
                changed = true;
            }
        }
        return changed;
    }

    private boolean settleLamps() {
        boolean changed = false;
        for (int i = 0; i < cellCount; i++) {
            Cell c = cells[i];
            if (!c.part.isSink()) {
                continue;
            }
            boolean lit = anyInput(i);
            if (c.powered != lit) {
                c.powered = lit;
                changed = true;
            }
        }
        return changed;
    }

    // --- per-part logic ---------------------------------------------------------------------------

    private boolean desiredTorchLit(int index) {
        Cell c = cells[index];
        int attach = neighbour(index, c.facing);
        if (attach < 0) {
            return true;
        }
        Cell support = cells[attach];
        if (!support.part.isFullBlock()) {
            return true;
        }
        return !(support.powered || support.weak);
    }

    private boolean desiredRepeaterPowered(int index) {
        return inputPower(index, cells[index].facing.opposite()) > 0;
    }

    private int desiredComparatorOutput(int index) {
        Cell c = cells[index];
        int back = comparatorInput(index, c.facing.opposite());
        int side = 0;
        for (Dir d : c.facing.perpendicularHorizontal()) {
            side = Math.max(side, comparatorInput(index, d));
        }
        if (c.subtract) {
            return Math.max(back - side, 0);
        }
        return back >= side ? back : 0;
    }

    /** Comparator inputs also see container/analog signals on the board boundary. */
    private int comparatorInput(int index, Dir fromDir) {
        int n = neighbour(index, fromDir);
        if (n < 0) {
            return Math.max(faceInput[fromDir.ordinal()], faceAnalog[fromDir.ordinal()]);
        }
        return Math.max(inputPower(index, fromDir), cells[n].analog);
    }

    private boolean anyInput(int index) {
        for (Dir d : Dir.VALUES) {
            if (inputPower(index, d) > 0) {
                return true;
            }
        }
        return false;
    }

    private int computeDustPower(int index) {
        // Non-wire signals from the six direct neighbours (vanilla getBestNeighborSignal, computed
        // with wires excluded).
        int best = 0;
        for (Dir d : Dir.VALUES) {
            int n = neighbour(index, d);
            if (n < 0) {
                best = Math.max(best, faceInput[d.ordinal()]);
                continue;
            }
            if (cells[n].part != Part.DUST) {
                best = Math.max(best, inputPower(index, d));
            }
        }
        // Adjacent wires, including one-block climbs and descents, decay by one.
        int j = 0;
        for (Dir d : Part.HORIZONTAL_ORDER) {
            int n = neighbour(index, d);
            if (n < 0) {
                continue;
            }
            j = Math.max(j, wirePower(n));
            if (isConductorAt(n)) {
                if (!isConductorAt(neighbour(index, Dir.UP))) {
                    j = Math.max(j, wirePower(neighbour(n, Dir.UP)));
                }
            } else {
                j = Math.max(j, wirePower(neighbour(n, Dir.DOWN)));
            }
        }
        return Math.max(0, Math.max(best, j - 1));
    }

    private int wirePower(int index) {
        return index >= 0 && cells[index].part == Part.DUST ? cells[index].power : 0;
    }

    private boolean isStronglyPowered(int solidIndex) {
        for (Dir d : Dir.VALUES) {
            int n = neighbour(solidIndex, d);
            if (n < 0) {
                continue;
            }
            Cell c = cells[n];
            if (!c.part.isStrongEmitter()) {
                continue;
            }
            if (emitPower(n, d.opposite()) > 0) {
                return true;
            }
        }
        return false;
    }

    private boolean isWeaklyPowered(int solidIndex) {
        int above = neighbour(solidIndex, Dir.UP);
        return above >= 0 && cells[above].part == Part.DUST && cells[above].power > 0;
    }

    /**
     * Power delivered to the cell at {@code index} from the direction {@code fromDir}.
     */
    private int inputPower(int index, Dir fromDir) {
        int n = neighbour(index, fromDir);
        if (n < 0) {
            return faceInput[fromDir.ordinal()];
        }
        Cell c = cells[n];
        int direct = emitPower(n, fromDir.opposite());
        if (direct > 0) {
            return direct;
        }
        if (c.part.isConductive() && c.powered) {
            return 15;
        }
        return 0;
    }

    /**
     * Power the cell at {@code index} pushes in direction {@code out}. For repeaters, comparators and
     * torches the direction is constrained by the part's facing/attachment.
     */
    private int emitPower(int index, Dir out) {
        Cell c = cells[index];
        return switch (c.part) {
            case AIR, SOLID, LAMP, GLASS, HOPPER, NOTE_BLOCK, FURNACE, BLAST_FURNACE, SMOKER,
                    BREWING_STAND, CRAFTER -> 0;
            case DUST -> dustEmitsTo(index, out) ? c.power : 0;
            case REDSTONE_BLOCK -> 15;
            case LEVER, BUTTON -> c.on ? 15 : 0;
            case OBSERVER -> (c.powered && out == c.facing.opposite()) ? 15 : 0;
            case REPEATER -> (c.powered && out == c.facing) ? 15 : 0;
            case COMPARATOR -> (c.powered && out == c.facing) ? c.power : 0;
            case TORCH -> {
                if (!c.powered) {
                    yield 0;
                }
                Dir face = c.facing;
                if (face.isHorizontal()) {
                    if (out == Dir.UP) {
                        yield 15;
                    }
                    yield (out.isHorizontal() && out != face && out != face.opposite()) ? 15 : 0;
                } else {
                    if (out.isHorizontal()) {
                        yield 15;
                    }
                    yield (out == face.opposite()) ? 15 : 0;
                }
            }
        };
    }

    // --- bulk helpers -----------------------------------------------------------------------------

    public boolean isEmpty() {
        for (Cell c : cells) {
            if (c.part != Part.AIR) {
                return false;
            }
        }
        return true;
    }

    public void clearAll() {
        for (Cell c : cells) {
            c.reset();
        }
        schedule.clear();
        for (int i = 0; i < faceInput.length; i++) {
            faceInput[i] = 0;
            faceAnalog[i] = 0;
        }
        markDirty();
    }
}
