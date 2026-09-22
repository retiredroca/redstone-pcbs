package com.retiredroca.redstonepcbs.craft;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * Survival availability: a part can be placed if the player holds the item, or if a crafting-table
 * recipe for it can be satisfied from their inventory (one level; smelting is not considered).
 */
public final class Crafting {
    private Crafting() {}

    public static boolean available(Level level, Player player, Item item) {
        if (player.isCreative() || count(player, item) > 0) {
            return true;
        }
        CraftingRecipe recipe = findRecipe(level, player, item);
        return recipe != null && hasIngredients(player, recipe);
    }

    /** Consumes the item, or one set of the recipe's ingredients. */
    public static boolean consume(Level level, Player player, Item item) {
        if (player.isCreative()) {
            return true;
        }
        if (consumeOne(player, item)) {
            return true;
        }
        CraftingRecipe recipe = findRecipe(level, player, item);
        if (recipe == null || !hasIngredients(player, recipe)) {
            return false;
        }
        consumeIngredients(player, recipe);
        return true;
    }

    private static CraftingRecipe findRecipe(Level level, Player player, Item item) {
        for (RecipeHolder<CraftingRecipe> holder : level.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)) {
            CraftingRecipe recipe = holder.value();
            if (!recipe.isSpecial() && recipe.getResultItem(level.registryAccess()).is(item)) {
                return recipe;
            }
        }
        return null;
    }

    private static List<ItemStack> pool(Player player) {
        List<ItemStack> pool = new ArrayList<>();
        for (ItemStack stack : player.getInventory().items) {
            if (!stack.isEmpty()) {
                pool.add(stack.copy());
            }
        }
        for (ItemStack stack : player.getInventory().offhand) {
            if (!stack.isEmpty()) {
                pool.add(stack.copy());
            }
        }
        return pool;
    }

    private static boolean hasIngredients(Player player, CraftingRecipe recipe) {
        List<ItemStack> pool = pool(player);
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (ingredient.isEmpty()) {
                continue;
            }
            boolean found = false;
            for (ItemStack stack : pool) {
                if (stack.getCount() > 0 && ingredient.test(stack)) {
                    stack.shrink(1);
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    private static void consumeIngredients(Player player, CraftingRecipe recipe) {
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (ingredient.isEmpty()) {
                continue;
            }
            outer:
            for (ItemStack stack : player.getInventory().items) {
                if (!stack.isEmpty() && ingredient.test(stack)) {
                    stack.shrink(1);
                    break outer;
                }
            }
            for (ItemStack stack : player.getInventory().offhand) {
                if (!stack.isEmpty() && ingredient.test(stack)) {
                    stack.shrink(1);
                    break;
                }
            }
        }
    }

    public static int count(Player player, Item item) {
        int count = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (stack.is(item)) {
                count += stack.getCount();
            }
        }
        for (ItemStack stack : player.getInventory().offhand) {
            if (stack.is(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static boolean consumeOne(Player player, Item item) {
        for (ItemStack stack : player.getInventory().items) {
            if (stack.is(item)) {
                stack.shrink(1);
                return true;
            }
        }
        for (ItemStack stack : player.getInventory().offhand) {
            if (stack.is(item)) {
                stack.shrink(1);
                return true;
            }
        }
        return false;
    }
}
