package com.retiredroca.redstonepcbs.dimension;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The board dimension's chunk generator: emits nothing but air. No terrain, no fluid, no carvers,
 * no surface, no features, no structures, no mobs. Each board's 16x16x16 region is therefore always
 * surrounded by air and never reads neighbouring terrain.
 *
 * <p>It is registered as a built-in generator codec at server start (see
 * {@link RuntimeDimension#ensure}), so it is never a datapack entry.
 */
public final class PcbChunkGenerator extends ChunkGenerator {
    /** The codec registered into {@code BuiltInRegistries.CHUNK_GENERATOR}. */
    public static final MapCodec<PcbChunkGenerator> CODEC = FixedBiomeSource.CODEC.xmap(
            PcbChunkGenerator::new,
            generator -> (FixedBiomeSource) generator.getBiomeSource());

    public PcbChunkGenerator(BiomeSource source) {
        super(source);
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    @Override
    public void applyCarvers(WorldGenRegion region, long seed, RandomState random, BiomeManager biomeManager,
            StructureManager structures, ChunkAccess chunk, GenerationStep.Carving step) {
    }

    @Override
    public void buildSurface(WorldGenRegion region, StructureManager structures, RandomState random, ChunkAccess chunk) {
    }

    @Override
    public void spawnOriginalMobs(WorldGenRegion region) {
    }

    @Override
    public int getGenDepth() {
        return PcbDimension.HEIGHT;
    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState random, StructureManager structures,
            ChunkAccess chunk) {
        return CompletableFuture.completedFuture(chunk);
    }

    @Override
    public int getSeaLevel() {
        return PcbDimension.BOARD_BASE_Y - 1;
    }

    @Override
    public int getMinY() {
        return PcbDimension.MIN_Y;
    }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor height, RandomState random) {
        return PcbDimension.MIN_Y;
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor height, RandomState random) {
        BlockState[] column = new BlockState[height.getHeight()];
        java.util.Arrays.fill(column, Blocks.AIR.defaultBlockState());
        return new NoiseColumn(height.getMinBuildHeight(), column);
    }

    @Override
    public void addDebugScreenInfo(List<String> info, RandomState random, BlockPos pos) {
    }
}
