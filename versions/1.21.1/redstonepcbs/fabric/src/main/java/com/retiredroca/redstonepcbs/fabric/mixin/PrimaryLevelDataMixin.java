package com.retiredroca.redstonepcbs.fabric.mixin;

import com.retiredroca.redstonepcbs.dimension.PcbDimension;

import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.storage.PrimaryLevelData;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps the board dimension out of the saved {@code WorldGenSettings.dimensions}.
 *
 * <p>This is what makes the runtime dimension warning-free: a dimension present in
 * {@code WorldGenSettings.dimensions} is decoded by {@code RegistryDataLoader} on load and, coming
 * from a mod pack, is marked {@code experimental}, which triggers the world warning. Removing our
 * key before the tag is written means the saved data never references it, so no warning appears.
 */
@Mixin(PrimaryLevelData.class)
public abstract class PrimaryLevelDataMixin {
    @Inject(method = "setTagData", at = @At("RETURN"))
    private void redstonepcbs$hideBoardDimension(RegistryAccess registries, CompoundTag tag,
            @Nullable CompoundTag playerTag, CallbackInfo ci) {
        CompoundTag dimensions = tag.getCompound("WorldGenSettings").getCompound("dimensions");
        dimensions.remove(PcbDimension.ID.toString());
    }
}
