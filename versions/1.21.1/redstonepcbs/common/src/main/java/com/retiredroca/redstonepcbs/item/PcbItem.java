package com.retiredroca.redstonepcbs.item;

import com.retiredroca.redstonepcbs.net.ModNetwork;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

/**
 * PCB item. Placing it creates a board (seeding the circuit from this item's {@code chip}
 * component); using it in the air opens the editor for the held item.
 */
public class PcbItem extends BlockItem {
    public PcbItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (!level.isClientSide && hand == InteractionHand.MAIN_HAND && player instanceof ServerPlayer serverPlayer) {
            ModNetwork.openItemEditor(serverPlayer, serverPlayer.getInventory().selected);
        }
        return InteractionResultHolder.success(player.getItemInHand(hand));
    }
}
