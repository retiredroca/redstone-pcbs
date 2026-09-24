package com.retiredroca.redstonepcbs.platform;

import com.retiredroca.redstonepcbs.block.PcbBlockEntity;
import com.retiredroca.redstonepcbs.data.ChipData;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.BlockEntityType;

/**
 * Loader-specific services used by the shared (common) code. The common sources are relocated per
 * loader and may not reference Fabric/NeoForge classes, so every loader-specific operation goes
 * through this interface.
 */
public interface RedstonePcbsPlatform {
    String loaderName();

    boolean isDevelopmentEnvironment();

    /** The loader's config directory (where {@code redstonepcbs.json} is written). */
    java.nio.file.Path configDirectory();

    /** The game/run directory (where exported blueprints are written). */
    java.nio.file.Path gameDirectory();

    /**
     * Registers the mod's blocks, items, block entity type and data component. Because NeoForge
     * requires registration through deferred holders, the created objects are exposed back through
     * {@link #pcbBlockEntityType()} and {@link #chip()}.
     */
    void registerContent();

    /** Registers payload types and the server-side network handlers. */
    void registerNetwork();

    BlockEntityType<PcbBlockEntity> pcbBlockEntityType();

    DataComponentType<ChipData> chip();

    Item pcbItem();

    <T extends CustomPacketPayload> void sendToPlayer(ServerPlayer player, T payload);

    /** Client-only in practice; called from the editor screen. */
    <T extends CustomPacketPayload> void sendToServer(T payload);
}
