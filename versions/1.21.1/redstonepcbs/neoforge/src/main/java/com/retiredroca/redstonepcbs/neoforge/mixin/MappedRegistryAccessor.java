package com.retiredroca.redstonepcbs.neoforge.mixin;

import net.minecraft.core.MappedRegistry;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets the runtime dimension register its values into a registry that has already been frozen. */
@Mixin(MappedRegistry.class)
public interface MappedRegistryAccessor {
    @Accessor("frozen")
    boolean redstonepcbs$isFrozen();

    @Accessor("frozen")
    void redstonepcbs$setFrozen(boolean frozen);
}
