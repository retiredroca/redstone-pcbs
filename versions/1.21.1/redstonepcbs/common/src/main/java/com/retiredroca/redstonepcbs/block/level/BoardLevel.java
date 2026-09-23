package com.retiredroca.redstonepcbs.block.level;

import com.retiredroca.redstonepcbs.block.BoardStates;
import com.retiredroca.redstonepcbs.block.Directions;
import com.retiredroca.redstonepcbs.block.PcbBlockEntity;
import com.retiredroca.redstonepcbs.chip.Cell;
import com.retiredroca.redstonepcbs.chip.ChipWorld;
import com.retiredroca.redstonepcbs.chip.Dir;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.level.storage.WritableLevelData;
import net.minecraft.world.scores.Scoreboard;

import org.jetbrains.annotations.Nullable;

/**
 * A virtual {@link Level} that presents the board's 16x16x16 voxel grid as if it were real world
 * blocks, so vanilla block-entity logic (furnaces, brewing stands, crafters, hoppers) can be run
 * verbatim against it. Cell {@code (x,y,z)} maps to {@code boardPos.offset(x,y,z)}; a position one
 * step outside the grid maps to the real world block on the corresponding board face.
 *
 * <p>Block updates are captured instead of written: {@link #setBlock} is a no-op and
 * {@link #blockEntityChanged} marks the board dirty, so the vanilla ticks never touch the world.
 */
public final class BoardLevel extends Level {
    private static final int SIZE = ChipWorld.SIZE;

    private final ServerLevel real;
    private final PcbBlockEntity board;

    public BoardLevel(ServerLevel real, PcbBlockEntity board) {
        super((WritableLevelData) real.getLevelData(),
                real.dimension(),
                real.registryAccess(),
                real.dimensionTypeRegistration(),
                real.getProfilerSupplier(),
                false,
                real.isDebug(),
                real.getSeed(),
                0);
        this.real = real;
        this.board = board;
    }

    public ServerLevel real() {
        return real;
    }

    public PcbBlockEntity board() {
        return board;
    }

    /** The virtual world position of a cell index. */
    public BlockPos vposOf(int index) {
        ChipWorld chip = board.chip();
        return board.getBlockPos().offset(chip.xOf(index), chip.yOf(index), chip.zOf(index));
    }

    /** Cell index for a virtual position inside the grid, or -1 if it is outside. */
    private int cellOf(BlockPos pos) {
        BlockPos origin = board.getBlockPos();
        int dx = pos.getX() - origin.getX();
        int dy = pos.getY() - origin.getY();
        int dz = pos.getZ() - origin.getZ();
        if (dx < 0 || dx >= SIZE || dy < 0 || dy >= SIZE || dz < 0 || dz >= SIZE) {
            return -1;
        }
        return board.chip().index(dx, dy, dz);
    }

    /** The board face a virtual position sits just outside of, or null. */
    @Nullable
    private Dir faceDir(BlockPos pos) {
        BlockPos origin = board.getBlockPos();
        int dx = pos.getX() - origin.getX();
        int dy = pos.getY() - origin.getY();
        int dz = pos.getZ() - origin.getZ();
        boolean xOut = dx < 0 || dx >= SIZE;
        boolean yOut = dy < 0 || dy >= SIZE;
        boolean zOut = dz < 0 || dz >= SIZE;
        if ((xOut ? 1 : 0) + (yOut ? 1 : 0) + (zOut ? 1 : 0) != 1) {
            return null;
        }
        if (xOut) {
            return dx == -1 ? Dir.WEST : dx == SIZE ? Dir.EAST : null;
        }
        if (yOut) {
            return dy == -1 ? Dir.DOWN : dy == SIZE ? Dir.UP : null;
        }
        return dz == -1 ? Dir.NORTH : dz == SIZE ? Dir.SOUTH : null;
    }

    private BlockPos realFacePos(Dir face) {
        return board.getBlockPos().relative(Directions.toMinecraft(face));
    }

    // --- board-local view -------------------------------------------------------------------------

    @Override
    public BlockState getBlockState(BlockPos pos) {
        int cell = cellOf(pos);
        if (cell >= 0) {
            return BoardStates.of(board.chip().cell(cell));
        }
        Dir face = faceDir(pos);
        if (face != null) {
            return real.getBlockState(realFacePos(face));
        }
        return Blocks.AIR.defaultBlockState();
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        Dir face = faceDir(pos);
        return face == null ? net.minecraft.world.level.material.Fluids.EMPTY.defaultFluidState()
                : real.getFluidState(realFacePos(face));
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        int cell = cellOf(pos);
        if (cell >= 0) {
            return board.componentEntity(cell);
        }
        Dir face = faceDir(pos);
        return face == null ? null : real.getBlockEntity(realFacePos(face));
    }

    @Override
    public boolean setBlock(BlockPos pos, BlockState state, int flags) {
        // Capture only: the vanilla tick's lit/crafting/bottle state is mirrored onto the cell, and
        // nothing is written to the world.
        int cell = cellOf(pos);
        if (cell < 0) {
            return false;
        }
        Cell c = board.chip().cell(cell);
        if (state.hasProperty(net.minecraft.world.level.block.AbstractFurnaceBlock.LIT)) {
            c.powered = state.getValue(net.minecraft.world.level.block.AbstractFurnaceBlock.LIT);
        }
        if (state.hasProperty(net.minecraft.world.level.block.CrafterBlock.CRAFTING)) {
            c.on = state.getValue(net.minecraft.world.level.block.CrafterBlock.CRAFTING);
        }
        if (state.hasProperty(net.minecraft.world.level.block.BrewingStandBlock.HAS_BOTTLE[0])) {
            int mask = 0;
            for (int i = 0; i < 3; i++) {
                if (state.getValue(net.minecraft.world.level.block.BrewingStandBlock.HAS_BOTTLE[i])) {
                    mask |= 1 << i;
                }
            }
            c.power = mask;
        }
        board.chip().markDirty();
        board.onComponentChanged();
        return true;
    }

    @Override
    public void blockEntityChanged(BlockPos pos) {
        board.onComponentChanged();
    }

    @Override
    public void gameEvent(Holder<net.minecraft.world.level.gameevent.GameEvent> event,
            net.minecraft.world.phys.Vec3 position,
            net.minecraft.world.level.gameevent.GameEvent.Context context) {}

    // --- delegated / inert ------------------------------------------------------------------------

    @Override
    public void sendBlockUpdated(BlockPos pos, BlockState oldState, BlockState newState, int flags) {}

    @Override
    public void playSeededSound(@Nullable Player player, double x, double y, double z,
            Holder<SoundEvent> sound, SoundSource source, float volume, float pitch, long seed) {}

    @Override
    public void playSeededSound(@Nullable Player player, Entity entity, Holder<SoundEvent> sound,
            SoundSource source, float volume, float pitch, long seed) {}

    @Override
    public String gatherChunkSourceStats() {
        return "";
    }

    @Override
    public Entity getEntity(int id) {
        return null;
    }

    @Override
    public TickRateManager tickRateManager() {
        return real.tickRateManager();
    }

    @Override
    public MapItemSavedData getMapData(MapId id) {
        return real.getMapData(id);
    }

    @Override
    public void setMapData(MapId id, MapItemSavedData data) {
        real.setMapData(id, data);
    }

    @Override
    public MapId getFreeMapId() {
        return real.getFreeMapId();
    }

    @Override
    public void destroyBlockProgress(int id, BlockPos pos, int progress) {}

    @Override
    public void levelEvent(@Nullable Player player, int type, BlockPos pos, int data) {}

    @Override
    public Scoreboard getScoreboard() {
        return real.getScoreboard();
    }

    @Override
    public RecipeManager getRecipeManager() {
        return real.getRecipeManager();
    }

    @Override
    protected LevelEntityGetter<Entity> getEntities() {
        return EMPTY_ENTITIES;
    }

    /** The board has no entities of its own; queries return nothing. */
    private static final LevelEntityGetter<Entity> EMPTY_ENTITIES = new LevelEntityGetter<Entity>() {
        @Override
        public Entity get(int id) {
            return null;
        }

        @Override
        public Entity get(java.util.UUID id) {
            return null;
        }

        @Override
        public Iterable<Entity> getAll() {
            return java.util.List.of();
        }

        @Override
        public <U extends Entity> void get(net.minecraft.world.level.entity.EntityTypeTest<Entity, U> test,
                net.minecraft.util.AbortableIterationConsumer<U> out) {}

        @Override
        public void get(net.minecraft.world.phys.AABB box, java.util.function.Consumer<Entity> out) {}

        @Override
        public <U extends Entity> void get(net.minecraft.world.level.entity.EntityTypeTest<Entity, U> test,
                net.minecraft.world.phys.AABB box, net.minecraft.util.AbortableIterationConsumer<U> out) {}
    };

    @Override
    public PotionBrewing potionBrewing() {
        return real.potionBrewing();
    }

    @Override
    public ChunkSource getChunkSource() {
        return real.getChunkSource();
    }

    @Override
    public net.minecraft.world.ticks.LevelTickAccess<net.minecraft.world.level.material.Fluid> getFluidTicks() {
        return real.getFluidTicks();
    }

    @Override
    public net.minecraft.world.ticks.LevelTickAccess<net.minecraft.world.level.block.Block> getBlockTicks() {
        return real.getBlockTicks();
    }

    @Override
    public java.util.List<? extends Player> players() {
        return real.players();
    }

    @Override
    public net.minecraft.world.flag.FeatureFlagSet enabledFeatures() {
        return real.enabledFeatures();
    }

    @Override
    public net.minecraft.world.level.chunk.ChunkAccess getChunk(int x, int z,
            net.minecraft.world.level.chunk.status.ChunkStatus status, boolean load) {
        return real.getChunk(x, z, status, load);
    }

    @Override
    public boolean hasChunk(int x, int z) {
        return real.hasChunk(x, z);
    }

    @Override
    public int getHeight(net.minecraft.world.level.levelgen.Heightmap.Types type, int x, int z) {
        return real.getHeight(type, x, z);
    }

    @Override
    public int getSkyDarken() {
        return real.getSkyDarken();
    }

    @Override
    public net.minecraft.world.level.biome.BiomeManager getBiomeManager() {
        return real.getBiomeManager();
    }

    @Override
    public Holder<net.minecraft.world.level.biome.Biome> getUncachedNoiseBiome(int x, int y, int z) {
        return real.getUncachedNoiseBiome(x, y, z);
    }

    @Override
    public int getSeaLevel() {
        return real.getSeaLevel();
    }

    @Override
    public DimensionType dimensionType() {
        return real.dimensionType();
    }

    @Override
    public int getHeight() {
        return real.getHeight();
    }

    @Override
    public int getMinBuildHeight() {
        return real.getMinBuildHeight();
    }

    @Override
    public float getShade(net.minecraft.core.Direction direction, boolean shade) {
        return real.getShade(direction, shade);
    }

    @Override
    public net.minecraft.world.level.lighting.LevelLightEngine getLightEngine() {
        return real.getLightEngine();
    }

    @Override
    public int getBlockTint(BlockPos pos, net.minecraft.world.level.ColorResolver resolver) {
        return real.getBlockTint(pos, resolver);
    }

    // NeoForge adds these to Level; Fabric/vanilla do not, so they are declared without @Override
    // (they satisfy NeoForge's abstract methods and are inert extra methods on Fabric).
    public void setDayTimeFraction(float fraction) {}

    public float getDayTimeFraction() {
        return 0.0F;
    }

    public float getDayTimePerTick() {
        return 1.0F;
    }

    public void setDayTimePerTick(float ticks) {}
}
