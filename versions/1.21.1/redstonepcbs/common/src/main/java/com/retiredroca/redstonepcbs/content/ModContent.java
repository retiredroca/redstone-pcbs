package com.retiredroca.redstonepcbs.content;

import com.retiredroca.redstonepcbs.RedstonePcbs;

import net.minecraft.resources.ResourceLocation;

/** Registry ids shared by both loaders. The objects themselves are created by each loader. */
public final class ModContent {
    public static final ResourceLocation PCB_BLOCK_ID = id("pcb");
    public static final ResourceLocation PCB_ITEM_ID = id("pcb");
    public static final ResourceLocation PCB_BE_ID = id("pcb");
    public static final ResourceLocation CHIP_ID = id("chip");

    private ModContent() {}

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(RedstonePcbs.MOD_ID, path);
    }
}
