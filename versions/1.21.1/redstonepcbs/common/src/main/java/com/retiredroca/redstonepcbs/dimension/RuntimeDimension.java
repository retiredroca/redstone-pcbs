package com.retiredroca.redstonepcbs.dimension;

import com.mojang.serialization.MapCodec;
import com.retiredroca.redstonepcbs.RedstonePcbs;
import com.retiredroca.redstonepcbs.block.BoardChunks;

import net.minecraft.core.Holder;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates and registers the board dimension at server start.
 *
 * <p>The dimension is registered straight into the live {@code DIMENSION_TYPE} and
 * {@code LEVEL_STEM} registries with {@link RegistrationInfo#BUILT_IN} (after temporarily
 * unfreezing the registry through the loader hook), and its key is stripped back out of the saved
 * {@code WorldGenSettings.dimensions} by the loader's level-data mixin. Because it is never a
 * datapack entry, the world's worldgen lifecycle stays stable and no experimental-settings warning
 * appears.
 *
 * <p>The loader supplies only the two things that need mixin access: unfreezing a registry to
 * register a value, and building/registering the {@link ServerLevel} against the running server.
 */
public final class RuntimeDimension {
    private static final Logger LOGGER = LoggerFactory.getLogger("redstonepcbs");

    /** Loader hook for the operations that need access to server internals. */
    public interface Access {
        /** Registers {@code value} into a (temporarily unfrozen) registry under {@code key}. */
        <T> Holder.Reference<T> register(Registry<T> registry, ResourceKey<T> key, T value);

        /** Builds a level for {@code key}/{@code stem} and registers it on the running server. */
        ServerLevel createAndRegisterLevel(MinecraftServer server, ResourceKey<net.minecraft.world.level.Level> key,
                LevelStem stem);
    }

    private RuntimeDimension() {}

    /** The registered board level, or null if the dimension could not be created. */
    public static ServerLevel level(MinecraftServer server) {
        return server == null ? null : server.getLevel(PcbDimension.LEVEL_KEY);
    }

    /**
     * Ensures the board dimension exists for this server. Safe to call more than once: returns the
     * existing level if present. Called from the loader's {@code prepareLevels} hook.
     */
    public static ServerLevel ensure(MinecraftServer server, Access access) {
        ServerLevel existing = server.getLevel(PcbDimension.LEVEL_KEY);
        if (existing != null) {
            // Ensure the ticket + forced-chunk pair is live on every start, not just at allocation.
            // restore() re-issues the region ticket (block/entity ticking) and re-forces the chunk
            // (keeps ServerLevel's keep-alive flag true so hoppers/containers tick forever, no player
            // ever needs to be in the board dimension). Without this the board loads but block entities
            // only tick inside the 300-tick (15 s) empty-time grace, then stop.
            BoardChunks.get(existing);
            return existing;
        }

        Registry<DimensionType> types = server.registryAccess().registryOrThrow(Registries.DIMENSION_TYPE);
        Registry<LevelStem> stems = server.registries().compositeAccess().registryOrThrow(Registries.LEVEL_STEM);

        ensureGeneratorCodec(access);

        // The dimension already being present in the registries but not the level map means a
        // previous load registered it; reuse those values rather than double-registering.
        Holder.Reference<DimensionType> typeHolder = types.containsKey(PcbDimension.ID)
                ? types.getHolderOrThrow(PcbDimension.TYPE_KEY)
                : access.register(types, PcbDimension.TYPE_KEY, PcbDimension.type());

        LevelStem stem = stems.containsKey(PcbDimension.ID)
                ? stems.get(PcbDimension.ID)
                : buildStem(server, typeHolder);

        if (!stems.containsKey(PcbDimension.ID)) {
            access.register(stems, ResourceKey.create(Registries.LEVEL_STEM, PcbDimension.ID), stem);
        }

        ServerLevel level = access.createAndRegisterLevel(server, PcbDimension.LEVEL_KEY, stem);
        if (level != null) {
            BoardChunks.get(level);
            LOGGER.info("PCB: created board dimension {} ({}..{}), base Y {}",
                    PcbDimension.ID, PcbDimension.MIN_Y, PcbDimension.MIN_Y + PcbDimension.HEIGHT,
                    PcbDimension.BOARD_BASE_Y);
        } else {
            LOGGER.error("PCB: failed to create board dimension {}", PcbDimension.ID);
        }
        return level;
    }

    private static LevelStem buildStem(MinecraftServer server, Holder<DimensionType> typeHolder) {
        Holder<net.minecraft.world.level.biome.Biome> voidBiome = server.registryAccess()
                .registryOrThrow(Registries.BIOME)
                .getHolderOrThrow(PcbDimension.voidBiomeKey());
        ChunkGenerator generator = PcbDimension.generator(voidBiome);
        return new LevelStem(typeHolder, generator);
    }

    /**
     * Registers the board generator's codec into the frozen built-in generator registry. Required
     * before the level stem's generator is serialised/validated; uses the loader hook so the
     * registry can be temporarily unfrozen.
     */
    @SuppressWarnings("unchecked")
    private static void ensureGeneratorCodec(Access access) {
        Registry<MapCodec<? extends ChunkGenerator>> registry = BuiltInRegistries.CHUNK_GENERATOR;
        ResourceLocation name = ResourceLocation.fromNamespaceAndPath(
                RedstonePcbs.MOD_ID, "pcb");
        if (!registry.containsKey(name)) {
            access.register(registry, ResourceKey.create(Registries.CHUNK_GENERATOR, name),
                    (MapCodec<? extends ChunkGenerator>) (MapCodec<?>) PcbChunkGenerator.CODEC);
        }
    }
}
