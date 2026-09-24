package com.retiredroca.redstonepcbs.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Removes obsolete dimension entries from a world's saved {@code level.dat}. Earlier builds of this
 * mod registered a {@code redstonepcbs:pcb} dimension; a world saved by such a build has that
 * dimension written into {@code WorldGenSettings.dimensions}, and once the dimension is gone
 * Minecraft logs {@code Failed to decode key ...} while loading the world. The stale key is dropped
 * on the next server start so the world self-heals. The game tolerates the key anyway; this only
 * keeps the log clean.
 */
public final class LevelDataScrub {
    private static final Logger LOGGER = LoggerFactory.getLogger("redstonepcbs");
    private static final String WORLD_GEN_SETTINGS = "WorldGenSettings";
    private static final String DIMENSIONS = "dimensions";
    private static final String MOD_DIMENSION = "redstonepcbs:pcb";

    private LevelDataScrub() {}

    /** Scrubs {@code level.dat} at {@code worldDir}, returning true if it was rewritten. */
    public static boolean scrub(Path worldDir) {
        Path levelDat = worldDir.resolve("level.dat");
        if (!Files.isRegularFile(levelDat)) {
            return false;
        }
        try {
            CompoundTag root = NbtIo.readCompressed(levelDat, NbtAccounter.unlimitedHeap());
            CompoundTag data = root.getCompound("Data");
            CompoundTag dimensions = data.getCompound(WORLD_GEN_SETTINGS).getCompound(DIMENSIONS);
            if (dimensions.isEmpty() || !dimensions.contains(MOD_DIMENSION)) {
                return false;
            }
            dimensions.remove(MOD_DIMENSION);
            Path temp = Files.createTempFile(worldDir, "level", ".dat");
            NbtIo.writeCompressed(root, temp);
            Path old = worldDir.resolve("level.dat_old");
            Files.deleteIfExists(old);
            Files.move(levelDat, old);
            Files.move(temp, levelDat);
            LOGGER.info("redstonepcbs: removed obsolete '{}' dimension from {}", MOD_DIMENSION, levelDat);
            return true;
        } catch (IOException e) {
            LOGGER.warn("redstonepcbs: could not scrub {}: {}", levelDat, e.toString());
            return false;
        }
    }
}
