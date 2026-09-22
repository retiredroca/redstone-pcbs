package com.retiredroca.redstonepcbs.data;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** World-level, per-player store of saved PCB designs. Works identically in single-player. */
public class LibraryData extends SavedData {
    public record Design(String name, byte[] data) {}

    private static final String NAME = "redstonepcbs_library";

    private final Map<UUID, List<Design>> byPlayer = new HashMap<>();

    public static LibraryData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage()
                .computeIfAbsent(new SavedData.Factory<>(LibraryData::new, LibraryData::load, null), NAME);
    }

    public List<Design> designs(UUID player) {
        return byPlayer.computeIfAbsent(player, key -> new ArrayList<>());
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag players = new ListTag();
        for (Map.Entry<UUID, List<Design>> entry : byPlayer.entrySet()) {
            CompoundTag player = new CompoundTag();
            player.putUUID("player", entry.getKey());
            ListTag list = new ListTag();
            for (Design design : entry.getValue()) {
                CompoundTag designTag = new CompoundTag();
                designTag.putString("name", design.name());
                designTag.putByteArray("data", design.data());
                list.add(designTag);
            }
            player.put("designs", list);
            players.add(player);
        }
        tag.put("players", players);
        return tag;
    }

    private static LibraryData load(CompoundTag tag, HolderLookup.Provider registries) {
        LibraryData data = new LibraryData();
        ListTag players = tag.getList("players", Tag.TAG_COMPOUND);
        for (int i = 0; i < players.size(); i++) {
            CompoundTag player = players.getCompound(i);
            UUID id = player.getUUID("player");
            List<Design> list = new ArrayList<>();
            ListTag designs = player.getList("designs", Tag.TAG_COMPOUND);
            for (int j = 0; j < designs.size(); j++) {
                CompoundTag design = designs.getCompound(j);
                list.add(new Design(design.getString("name"), design.getByteArray("data")));
            }
            data.byPlayer.put(id, list);
        }
        return data;
    }
}
