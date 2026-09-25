package com.retiredroca.redstonepcbs.block;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.BlastFurnaceMenu;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.CrafterMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.DispenserMenu;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.inventory.HopperMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.SmokerMenu;
import net.minecraft.world.entity.player.StackedContents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlastFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.CrafterBlockEntity;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.entity.SmokerBlockEntity;

import java.lang.reflect.Field;
import java.util.List;

/**
 * Opens a container block's vanilla menu for a board. The board's block entities live in the board
 * dimension, so the vanilla distance check in {@code stillValid} would close the menu instantly; we
 * open the same menu against a thin container wrapper that reports valid.
 */
public final class BoardMenus {
    private BoardMenus() {}

    /** Opens the vanilla menu for {@code be}; returns false if it has none. */
    public static boolean open(ServerPlayer player, BlockEntity be) {
        Component title = be.getBlockState().getBlock().getName();
        if (be instanceof CrafterBlockEntity crafter) {
            return openCrafter(player, crafter, title);
        }
        if (be instanceof AbstractFurnaceBlockEntity furnace) {
            return openFurnace(player, furnace, title);
        }
        if (be instanceof BrewingStandBlockEntity brewing) {
            return open(player, title, (id, inv) -> new BrewingStandMenu(id, inv,
                    new Wrapper(brewing), data(brewing)));
        }
        if (be instanceof ChestBlockEntity chest) {
            // threeRows, matching ChestBlockEntity.createMenu: a single chest has 9 slots. The
            // 27-slot variant is only valid once a double chest has actually paired its two halves.
            return open(player, title, (id, inv) ->
                    ChestMenu.threeRows(id, inv, new Wrapper(chest)));
        }
        if (be instanceof BarrelBlockEntity barrel) {
            return open(player, title, (id, inv) ->
                    ChestMenu.threeRows(id, inv, new Wrapper(barrel)));
        }
        // DropperBlockEntity extends DispenserBlockEntity, so this covers both.
        if (be instanceof DispenserBlockEntity dispenser) {
            return open(player, title, (id, inv) -> new DispenserMenu(id, inv, new Wrapper(dispenser)));
        }
        if (be instanceof ShulkerBoxBlockEntity shulker) {
            return open(player, title, (id, inv) -> new ShulkerBoxMenu(id, inv, new Wrapper(shulker)));
        }
        if (be instanceof Container container) {
            return open(player, title, (id, inv) -> new HopperMenu(id, inv, new Wrapper(container)));
        }
        return false;
    }

    private static boolean openFurnace(ServerPlayer player, AbstractFurnaceBlockEntity furnace, Component title) {
        return open(player, title, (id, inv) -> {
            if (furnace instanceof SmokerBlockEntity) {
                return new SmokerMenu(id, inv, new Wrapper(furnace), data(furnace));
            }
            if (furnace instanceof BlastFurnaceBlockEntity) {
                return new BlastFurnaceMenu(id, inv, new Wrapper(furnace), data(furnace));
            }
            return new FurnaceMenu(id, inv, new Wrapper(furnace), data(furnace));
        });
    }

    private static boolean openCrafter(ServerPlayer player, CrafterBlockEntity crafter, Component title) {
        return open(player, title, (id, inv) -> new CrafterMenu(id, inv,
                new CraftingWrapper(crafter), data(crafter)));
    }

    private interface MenuFactory {
        AbstractContainerMenu create(int id, Inventory inv);
    }

    private static boolean open(ServerPlayer player, Component title, MenuFactory factory) {
        player.openMenu(new MenuProvider() {
            @Override
            public Component getDisplayName() {
                return title;
            }

            @Override
            public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) {
                return factory.create(id, inv);
            }
        });
        return true;
    }

    /** Finds the block entity's own {@code ContainerData} (progress/fuel/slot states) by type. */
    private static net.minecraft.world.inventory.ContainerData data(BlockEntity be) {
        for (Class<?> type = be.getClass(); type != null && type != BlockEntity.class;
                type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (net.minecraft.world.inventory.ContainerData.class.isAssignableFrom(field.getType())) {
                    try {
                        field.setAccessible(true);
                        return (net.minecraft.world.inventory.ContainerData) field.get(be);
                    } catch (ReflectiveOperationException ignored) {
                        // fall through
                    }
                }
            }
        }
        return null;
    }

    /** Delegates everything to the block entity but always reports valid. */
    private record Wrapper(Container delegate) implements Container {
        @Override
        public int getContainerSize() {
            return delegate.getContainerSize();
        }

        @Override
        public boolean isEmpty() {
            return delegate.isEmpty();
        }

        @Override
        public ItemStack getItem(int slot) {
            return delegate.getItem(slot);
        }

        @Override
        public ItemStack removeItem(int slot, int amount) {
            return delegate.removeItem(slot, amount);
        }

        @Override
        public ItemStack removeItemNoUpdate(int slot) {
            return delegate.removeItemNoUpdate(slot);
        }

        @Override
        public void setItem(int slot, ItemStack stack) {
            delegate.setItem(slot, stack);
        }

        @Override
        public int getMaxStackSize() {
            return delegate.getMaxStackSize();
        }

        @Override
        public void setChanged() {
            delegate.setChanged();
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }

        @Override
        public void clearContent() {
            delegate.clearContent();
        }

        @Override
        public boolean canPlaceItem(int slot, ItemStack stack) {
            return delegate.canPlaceItem(slot, stack);
        }
    }

    private record CraftingWrapper(CraftingContainer delegate) implements CraftingContainer {
        @Override
        public int getWidth() {
            return delegate.getWidth();
        }

        @Override
        public int getHeight() {
            return delegate.getHeight();
        }

        @Override
        public List<ItemStack> getItems() {
            return delegate.getItems();
        }

        @Override
        public int getContainerSize() {
            return delegate.getContainerSize();
        }

        @Override
        public boolean isEmpty() {
            return delegate.isEmpty();
        }

        @Override
        public ItemStack getItem(int slot) {
            return delegate.getItem(slot);
        }

        @Override
        public ItemStack removeItem(int slot, int amount) {
            return delegate.removeItem(slot, amount);
        }

        @Override
        public ItemStack removeItemNoUpdate(int slot) {
            return delegate.removeItemNoUpdate(slot);
        }

        @Override
        public void setItem(int slot, ItemStack stack) {
            delegate.setItem(slot, stack);
        }

        @Override
        public int getMaxStackSize() {
            return delegate.getMaxStackSize();
        }

        @Override
        public void setChanged() {
            delegate.setChanged();
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }

        @Override
        public void clearContent() {
            delegate.clearContent();
        }

        @Override
        public boolean canPlaceItem(int slot, ItemStack stack) {
            return delegate.canPlaceItem(slot, stack);
        }

        @Override
        public void fillStackedContents(StackedContents contents) {
            delegate.fillStackedContents(contents);
        }
    }
}
