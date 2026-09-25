package com.retiredroca.redstonepcbs.block;

import com.retiredroca.redstonepcbs.chip.Dir;
import com.retiredroca.redstonepcbs.chip.Part;

import net.minecraft.core.Direction;
import net.minecraft.core.FrontAndTop;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ComparatorMode;
import net.minecraft.world.level.block.state.properties.DirectionProperty;


/**
 * The board's block palette: maps a {@link Part} plus a facing to the vanilla {@link BlockState} that
 * is placed in the board's backing region. From then on Minecraft owns the state and its behaviour.
 */
public final class BoardStates {
    private BoardStates() {}

    /** The initial state for placing {@code part} facing {@code facing}. */
    public static BlockState initial(Part part, Dir facing) {
        Dir f = part.sanitizeFacing(facing);
        return switch (part) {
            case AIR -> Blocks.AIR.defaultBlockState();
            case SOLID -> Blocks.STONE.defaultBlockState();
            case GLASS -> Blocks.GLASS.defaultBlockState();
            case REDSTONE_BLOCK -> Blocks.REDSTONE_BLOCK.defaultBlockState();
            case NOTE_BLOCK -> Blocks.NOTE_BLOCK.defaultBlockState();
            case LAMP -> Blocks.REDSTONE_LAMP.defaultBlockState();
            case DUST -> Blocks.REDSTONE_WIRE.defaultBlockState();
            case TORCH -> torchState(f);
            case REPEATER -> Blocks.REPEATER.defaultBlockState()
                    .setValue(RepeaterBlock.FACING, Directions.toMinecraft(f));
            case COMPARATOR -> Blocks.COMPARATOR.defaultBlockState()
                    .setValue(ComparatorBlock.FACING, Directions.toMinecraft(f))
                    .setValue(ComparatorBlock.MODE, ComparatorMode.COMPARE);
            case OBSERVER -> Blocks.OBSERVER.defaultBlockState()
                    .setValue(ObserverBlock.FACING, Directions.toMinecraft(f));
            case HOPPER -> Blocks.HOPPER.defaultBlockState()
                    .setValue(HopperBlock.FACING, Directions.toMinecraft(f));
            case LEVER -> Blocks.LEVER.defaultBlockState()
                    .setValue(BlockStateProperties.ATTACH_FACE, attachFace(f))
                    .setValue(HorizontalDirectionalBlock.FACING, attachFacing(f));
            case BUTTON -> Blocks.STONE_BUTTON.defaultBlockState()
                    .setValue(BlockStateProperties.ATTACH_FACE, attachFace(f))
                    .setValue(HorizontalDirectionalBlock.FACING, attachFacing(f));
            case FURNACE -> furnace(Blocks.FURNACE.defaultBlockState(), f);
            case BLAST_FURNACE -> furnace(Blocks.BLAST_FURNACE.defaultBlockState(), f);
            case SMOKER -> furnace(Blocks.SMOKER.defaultBlockState(), f);
            case BREWING_STAND -> Blocks.BREWING_STAND.defaultBlockState();
            case CRAFTER -> crafter(f);
        };
    }

    /** The vanilla item that places {@code part}, or {@code null} for air/unknown. */
    public static Item itemFor(Part part) {
        return switch (part) {
            case DUST -> Items.REDSTONE;
            case TORCH -> Items.REDSTONE_TORCH;
            case REPEATER -> Items.REPEATER;
            case COMPARATOR -> Items.COMPARATOR;
            case REDSTONE_BLOCK -> Items.REDSTONE_BLOCK;
            case LEVER -> Items.LEVER;
            case BUTTON -> Items.STONE_BUTTON;
            case SOLID -> Items.STONE;
            case LAMP -> Items.REDSTONE_LAMP;
            case OBSERVER -> Items.OBSERVER;
            case NOTE_BLOCK -> Items.NOTE_BLOCK;
            case GLASS -> Items.GLASS;
            case HOPPER -> Items.HOPPER;
            case FURNACE -> Items.FURNACE;
            case BLAST_FURNACE -> Items.BLAST_FURNACE;
            case SMOKER -> Items.SMOKER;
            case BREWING_STAND -> Items.BREWING_STAND;
            case CRAFTER -> Items.CRAFTER;
            default -> null;
        };
    }

    /** The palette part an item corresponds to, or {@code null} when it is not a known part. */
    public static Part partFor(Item item) {
        for (Part part : Part.VALUES) {
            if (itemFor(part) == item) {
                return part;
            }
        }
        return null;
    }

    /**
     * The state to place for {@code item} facing {@code facing}, or {@code null} when the item cannot
     * be placed on the board. A known part reuses its exact {@link #initial} state (so torch walls,
     * crafter orientation and lever attachment all survive); any other {@link BlockItem} is placed as
     * its default state with the facing applied where the block supports it.
     */
    public static BlockState forItem(Item item, Dir facing) {
        Part part = partFor(item);
        if (part != null && part != Part.AIR) {
            return initial(part, facing);
        }
        if (!(item instanceof BlockItem blockItem)) {
            return null;
        }
        BlockState state = blockItem.getBlock().defaultBlockState();
        Direction direction = Directions.toMinecraft(facing);
        DirectionProperty facingProperty = facingProperty(state);
        if (facingProperty != null && facingProperty.getPossibleValues().contains(direction)) {
            state = state.setValue(facingProperty, direction);
        }
        return state;
    }

    /**
     * Whether a state is one of the vanilla blocks whose block entity is a {@link
     * net.minecraft.world.Container}. The client has no level to ask, so the editor uses this to
     * decide whether a cell may be given a gateway face; the server re-checks against the real
     * container when it rebuilds the boundary slots.
     */
    public static boolean hasVanillaContainer(BlockState state) {
        return state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST) || state.is(Blocks.BARREL)
                || state.is(Blocks.DROPPER) || state.is(Blocks.DISPENSER) || state.is(Blocks.HOPPER)
                || state.is(Blocks.FURNACE) || state.is(Blocks.BLAST_FURNACE) || state.is(Blocks.SMOKER)
                || state.is(Blocks.BREWING_STAND) || state.is(Blocks.CRAFTER)
                || state.is(Blocks.JUKEBOX) || state.is(Blocks.CHISELED_BOOKSHELF)
                || state.is(Blocks.DECORATED_POT) || state.is(Blocks.VAULT)
                || state.is(Blocks.WHITE_SHULKER_BOX) || state.is(Blocks.ORANGE_SHULKER_BOX)
                || state.is(Blocks.MAGENTA_SHULKER_BOX) || state.is(Blocks.LIGHT_BLUE_SHULKER_BOX)
                || state.is(Blocks.YELLOW_SHULKER_BOX) || state.is(Blocks.LIME_SHULKER_BOX)
                || state.is(Blocks.PINK_SHULKER_BOX) || state.is(Blocks.GRAY_SHULKER_BOX)
                || state.is(Blocks.LIGHT_GRAY_SHULKER_BOX) || state.is(Blocks.CYAN_SHULKER_BOX)
                || state.is(Blocks.PURPLE_SHULKER_BOX) || state.is(Blocks.BLACK_SHULKER_BOX);
    }

    /** Whether {@code item} can be placed on the board (a known part, or any block item). */
    public static boolean isPlaceable(Item item) {
        return item != null && (partFor(item) != null || item instanceof BlockItem);
    }

    /** The first facing property a block state exposes (FACING, then HORIZONTAL_FACING), or null. */
    private static DirectionProperty facingProperty(BlockState state) {
        if (state.hasProperty(BlockStateProperties.FACING)) {
            return BlockStateProperties.FACING;
        }
        if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            return BlockStateProperties.HORIZONTAL_FACING;
        }
        return null;
    }

    /** The palette part that maps to a placed state, or AIR. Used by the editor's hover/selection. */
    public static Part partOf(BlockState state) {
        if (state.isAir()) {
            return Part.AIR;
        }
        if (state.is(Blocks.STONE)) return Part.SOLID;
        if (state.is(Blocks.GLASS)) return Part.GLASS;
        if (state.is(Blocks.REDSTONE_BLOCK)) return Part.REDSTONE_BLOCK;
        if (state.is(Blocks.NOTE_BLOCK)) return Part.NOTE_BLOCK;
        if (state.is(Blocks.REDSTONE_LAMP)) return Part.LAMP;
        if (state.is(Blocks.REDSTONE_WIRE)) return Part.DUST;
        if (state.is(Blocks.REDSTONE_TORCH) || state.is(Blocks.REDSTONE_WALL_TORCH)) return Part.TORCH;
        if (state.is(Blocks.REPEATER)) return Part.REPEATER;
        if (state.is(Blocks.COMPARATOR)) return Part.COMPARATOR;
        if (state.is(Blocks.OBSERVER)) return Part.OBSERVER;
        if (state.is(Blocks.HOPPER)) return Part.HOPPER;
        if (state.is(Blocks.LEVER)) return Part.LEVER;
        if (state.is(Blocks.STONE_BUTTON)) return Part.BUTTON;
        if (state.is(Blocks.FURNACE)) return Part.FURNACE;
        if (state.is(Blocks.BLAST_FURNACE)) return Part.BLAST_FURNACE;
        if (state.is(Blocks.SMOKER)) return Part.SMOKER;
        if (state.is(Blocks.BREWING_STAND)) return Part.BREWING_STAND;
        if (state.is(Blocks.CRAFTER)) return Part.CRAFTER;
        return Part.SOLID;
    }

    private static BlockState torchState(Dir facing) {
        if (facing == Dir.DOWN || facing == Dir.UP) {
            return Blocks.REDSTONE_TORCH.defaultBlockState();
        }
        // WallTorchBlock.FACING points away from the wall (opposite our attachment direction).
        return Blocks.REDSTONE_WALL_TORCH.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Directions.toMinecraft(facing.opposite()));
    }

    private static BlockState furnace(BlockState state, Dir facing) {
        return state.setValue(HorizontalDirectionalBlock.FACING, Directions.toMinecraft(facing));
    }

    private static BlockState crafter(Dir facing) {
        Direction front = Directions.toMinecraft(facing);
        Direction top = front.getAxis() == Direction.Axis.Y ? Direction.NORTH : Direction.UP;
        return Blocks.CRAFTER.defaultBlockState()
                .setValue(BlockStateProperties.ORIENTATION, FrontAndTop.fromFrontAndTop(front, top));
    }

    private static AttachFace attachFace(Dir facing) {
        return switch (facing) {
            case DOWN -> AttachFace.FLOOR;
            case UP -> AttachFace.CEILING;
            default -> AttachFace.WALL;
        };
    }

    private static Direction attachFacing(Dir facing) {
        return facing.isHorizontal() ? Directions.toMinecraft(facing.opposite()) : Direction.NORTH;
    }
}
