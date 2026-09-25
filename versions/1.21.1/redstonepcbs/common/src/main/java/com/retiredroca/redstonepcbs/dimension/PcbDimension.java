package com.retiredroca.redstonepcbs.dimension;

import com.retiredroca.redstonepcbs.RedstonePcbs;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.DimensionType;

import java.util.OptionalLong;

/**
 * Parameters for the board dimension: an empty, code-registered dimension that hosts each board's
 * 16x16x16 redstone region. Nothing here is a datapack entry, so it never passes through
 * {@code RegistryDataLoader} and never marks the world's worldgen lifecycle as experimental.
 *
 * <p>Everything is a named constant so the dimension can be widened later (a taller board) or
 * made to spawn mobs (self-contained mob farms) by changing values, not structure.
 */
public final class PcbDimension {
    private PcbDimension() {}

    /** Full-dimension id, e.g. {@code redstonepcbs:pcb}. */
    public static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(RedstonePcbs.MOD_ID, "pcb");
    /** Level key for the board dimension. */
    public static final ResourceKey<Level> LEVEL_KEY = ResourceKey.create(Registries.DIMENSION, ID);
    /** Dimension type key (registered at runtime, never persisted). */
    public static final ResourceKey<DimensionType> TYPE_KEY =
            ResourceKey.create(Registries.DIMENSION_TYPE, ID);

    // --- dimension shape (parameters for later expansion) -------------------------------------

    /** Lowest build Y of the dimension, so there is space under a board region at Y 0. */
    public static final int MIN_Y = -64;
    /** Total height; must be a multiple of 16. Full-height for future taller boards. */
    public static final int HEIGHT = 384;
    /** Logical height (fog/portal clamping); at most {@link #HEIGHT}. */
    public static final int LOGICAL_HEIGHT = HEIGHT;
    /**
     * World Y where a board region's bottom layer sits. Raised from 0 to keep boards clear of both
     * the -64..0 cellar and the top world border: with an all-air generator there is no terrain
     * collision, but the original working (End-era) config kept the board away from the bottom and
     * the borders. 288 puts the 16-tall board at 288..303 with a 16-block gap (304..319) to the
     * build ceiling (320). This is a single derivation point: {@code getSeaLevel()} and every board
     * base Y computation follow from it.
     */
    public static final int BOARD_BASE_Y = 288;

    /** Whether the dimension has skylight. Off: the board needs no light simulation. */
    public static final boolean SKYLIGHT = false;
    /** Fixed time of day, so the board always renders lit. Empty would follow the overworld. */
    public static final OptionalLong FIXED_TIME = OptionalLong.of(6000L);

    /**
     * Builds the dimension type. Code-level only; adjust the constants above to change the height.
     */
    public static DimensionType type() {
        DimensionType.MonsterSettings monsters = new DimensionType.MonsterSettings(
                false,                                  // piglinSafe
                false,                                  // hasRaids
                UniformInt.of(0, 7),                    // monsterSpawnLightTest
                0);                                     // monsterSpawnBlockLightLimit
        return new DimensionType(
                FIXED_TIME,                             // fixedTime
                SKYLIGHT,                               // hasSkyLight
                false,                                  // hasCeiling
                true,                                   // ultraWarm
                false,                                  // natural
                1.0,                                    // coordinateScale
                false,                                  // bedWorks
                false,                                  // respawnAnchorWorks
                MIN_Y,                                  // minY
                HEIGHT,                                 // height
                LOGICAL_HEIGHT,                         // logicalHeight
                BlockTags.INFINIBURN_OVERWORLD,         // infiniburn
                net.minecraft.world.level.dimension.BuiltinDimensionTypes.END_EFFECTS, // effectsLocation
                0.0F,                                   // ambientLight
                monsters);
    }

    /**
     * The dimension's chunk generator: {@link PcbChunkGenerator}, a no-op generator that emits only
     * air (no terrain, no fluid, no carvers, no surface, no features, no structures, no mobs). The
     * board's region is therefore always surrounded by air and never reads neighbouring terrain.
     *
     * <p>Its codec is registered into {@code BuiltInRegistries.CHUNK_GENERATOR} at server start by
     * {@link RuntimeDimension#ensure}, so it is code-level only and never a datapack entry.
     */
    public static ChunkGenerator generator(Holder<Biome> voidBiome) {
        return new PcbChunkGenerator(new FixedBiomeSource(voidBiome));
    }

    /** The {@code the_void} biome key used for the fixed biome source. */
    public static ResourceKey<Biome> voidBiomeKey() {
        return Biomes.THE_VOID;
    }
}
