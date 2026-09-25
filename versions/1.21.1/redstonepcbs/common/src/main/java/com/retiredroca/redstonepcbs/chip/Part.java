package com.retiredroca.redstonepcbs.chip;

/**
 * The kind of part occupying a voxel. Ordinals are persisted, so only append (never reorder).
 *
 * <p>Each part declares its behaviour once, as a {@link FacingFamily}, a {@link ContainerFamily} and
 * a set of {@link Role} flags. Every predicate below is derived from those, so adding a part is a
 * single table entry instead of edits scattered across the engine, the renderer and the network.
 */
public enum Part {
    AIR(FacingFamily.NONE, ContainerFamily.NONE),
    SOLID(FacingFamily.NONE, ContainerFamily.NONE, Role.FULL_BLOCK, Role.CONDUCTIVE),
    DUST(FacingFamily.NONE, ContainerFamily.NONE, Role.DUST),
    TORCH(FacingFamily.TORCH, ContainerFamily.NONE, Role.STRONG_EMITTER, Role.DELAYED),
    REPEATER(FacingFamily.HORIZONTAL, ContainerFamily.NONE, Role.STRONG_EMITTER, Role.DELAYED),
    COMPARATOR(FacingFamily.HORIZONTAL, ContainerFamily.NONE, Role.STRONG_EMITTER, Role.DELAYED,
            Role.ANALOG),
    REDSTONE_BLOCK(FacingFamily.NONE, ContainerFamily.NONE, Role.FULL_BLOCK, Role.SOURCE,
            Role.STRONG_EMITTER),
    LEVER(FacingFamily.FACE_ATTACHED, ContainerFamily.NONE, Role.SOURCE, Role.STRONG_EMITTER,
            Role.TOGGLEABLE),
    BUTTON(FacingFamily.FACE_ATTACHED, ContainerFamily.NONE, Role.SOURCE, Role.STRONG_EMITTER,
            Role.TOGGLEABLE),
    LAMP(FacingFamily.NONE, ContainerFamily.NONE, Role.FULL_BLOCK, Role.SINK),
    OBSERVER(FacingFamily.SIX_WAY, ContainerFamily.NONE, Role.FULL_BLOCK, Role.STRONG_EMITTER,
            Role.DELAYED),
    NOTE_BLOCK(FacingFamily.NONE, ContainerFamily.NONE, Role.FULL_BLOCK, Role.SINK),
    GLASS(FacingFamily.NONE, ContainerFamily.NONE, Role.FULL_BLOCK),
    HOPPER(FacingFamily.HOPPER, ContainerFamily.HOPPER, Role.FULL_BLOCK, Role.CONDUCTIVE),
    FURNACE(FacingFamily.HORIZONTAL, ContainerFamily.COOKER, Role.FULL_BLOCK),
    BLAST_FURNACE(FacingFamily.HORIZONTAL, ContainerFamily.COOKER, Role.FULL_BLOCK),
    SMOKER(FacingFamily.HORIZONTAL, ContainerFamily.COOKER, Role.FULL_BLOCK),
    BREWING_STAND(FacingFamily.NONE, ContainerFamily.BREWING),
    CRAFTER(FacingFamily.SIX_WAY, ContainerFamily.CRAFTER, Role.FULL_BLOCK);

    /** How a part orients itself, mirroring the vanilla block family it represents. */
    public enum FacingFamily {
        /** No orientation (solid, dust, lamp, note block, brewing stand). */
        NONE,
        /** Four horizontal directions (repeater, comparator, furnace family). */
        HORIZONTAL,
        /** All six directions (observer, crafter). */
        SIX_WAY,
        /** Face plus horizontal facing, i.e. attached to a floor/ceiling/wall (lever, button). */
        FACE_ATTACHED,
        /** A torch: standing on a support, or a wall torch attached to one (down + four sides). */
        TORCH,
        /** A hopper: down or one of the four sides, never up. */
        HOPPER
    }

    /** The vanilla container/processor behaviour a part runs inside the board. */
    public enum ContainerFamily {
        NONE,
        /** 5-slot hopper; inserts and extracts through the board's gateway attachments. */
        HOPPER,
        /** Furnace/blast furnace/smoker: smelting with fuel and cook progress. */
        COOKER,
        BREWING,
        CRAFTER
    }

    /** Orthogonal behavioural flags; a part may hold several. */
    public enum Role {
        FULL_BLOCK,
        CONDUCTIVE,
        SOURCE,
        STRONG_EMITTER,
        DELAYED,
        TOGGLEABLE,
        DUST,
        SINK,
        ANALOG
    }

    public static final Dir[] SUPPORTED_ORDER = {Dir.DOWN, Dir.NORTH, Dir.SOUTH, Dir.WEST, Dir.EAST};
    public static final Dir[] HORIZONTAL_ORDER = {Dir.NORTH, Dir.EAST, Dir.SOUTH, Dir.WEST};
    public static final Dir[] ALL_ORDER = {Dir.UP, Dir.DOWN, Dir.NORTH, Dir.EAST, Dir.SOUTH, Dir.WEST};
    private static final Dir[] NO_ORDER = {};

    public static final Part[] VALUES = values();

    private final FacingFamily facing;
    private final ContainerFamily container;
    private final int roles;

    Part(FacingFamily facing, ContainerFamily container, Role... roles) {
        this.facing = facing;
        this.container = container;
        int mask = 0;
        for (Role role : roles) {
            mask |= 1 << role.ordinal();
        }
        this.roles = mask;
    }

    public FacingFamily facingFamily() {
        return facing;
    }

    public ContainerFamily containerFamily() {
        return container;
    }

    public boolean has(Role role) {
        return (roles & (1 << role.ordinal())) != 0;
    }

    // --- redstone roles -----------------------------------------------------------------------

    public boolean isSource() {
        return has(Role.SOURCE);
    }

    public boolean isStrongEmitter() {
        return has(Role.STRONG_EMITTER);
    }

    public boolean isDelayed() {
        return has(Role.DELAYED);
    }

    public boolean isToggleable() {
        return has(Role.TOGGLEABLE);
    }

    public boolean isFullBlock() {
        return has(Role.FULL_BLOCK);
    }

    public boolean isConductive() {
        return has(Role.CONDUCTIVE);
    }

    public boolean isDust() {
        return has(Role.DUST);
    }

    public boolean isSink() {
        return has(Role.SINK);
    }

    /** Comparator-style analog output (the comparator itself; containers report via their component). */
    public boolean isAnalogSource() {
        return has(Role.ANALOG);
    }

    public boolean isContainer() {
        return container != ContainerFamily.NONE;
    }

    // --- facing groups ------------------------------------------------------------------------

    public boolean needsSupport() {
        return facing == FacingFamily.TORCH;
    }

    public boolean isHorizontalOnly() {
        return facing == FacingFamily.HORIZONTAL;
    }

    public boolean pointsAtNeighbour() {
        return facing == FacingFamily.HOPPER;
    }

    public boolean isSixWay() {
        return facing == FacingFamily.SIX_WAY;
    }

    public boolean isFaceAttached() {
        return facing == FacingFamily.FACE_ATTACHED;
    }

    public boolean isRotatable() {
        return facing != FacingFamily.NONE;
    }

    /** The directions this part may face, in rotation order. */
    public Dir[] facingOrder() {
        return switch (facing) {
            case TORCH, HOPPER -> SUPPORTED_ORDER;
            case HORIZONTAL -> HORIZONTAL_ORDER;
            case SIX_WAY, FACE_ATTACHED -> ALL_ORDER;
            case NONE -> NO_ORDER;
        };
    }

    /**
     * Clamps a facing to the directions this part actually supports, so no code path can build an
     * invalid block state.
     */
    public Dir sanitizeFacing(Dir facing) {
        Dir f = facing == null ? Dir.DOWN : facing;
        return switch (this.facing) {
            case TORCH, HOPPER -> f == Dir.UP ? Dir.DOWN : f;
            case HORIZONTAL -> f.isHorizontal() ? f : Dir.NORTH;
            case SIX_WAY, FACE_ATTACHED, NONE -> f;
        };
    }

    public static Part byOrdinal(int ordinal) {
        int i = Math.floorMod(ordinal, VALUES.length);
        return VALUES[i];
    }
}
