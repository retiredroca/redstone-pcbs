package com.retiredroca.redstonepcbs.net;

import com.retiredroca.redstonepcbs.RedstonePcbs;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/** Server -> client copy of the player's saved-designs library. */
public record S2CLibraryPayload(List<Design> designs, boolean canSave, int limit, boolean canImport,
        List<String> players) implements CustomPacketPayload {
    /**
     * A saved design: its name, serialized grid, gateway attachment bytes, and the player who shared
     * it (empty for the owner's own designs).
     */
    public record Design(String name, byte[] data, byte[] faces, byte[] ports, String author) {
        public Design(String name, byte[] data) {
            this(name, data, new byte[0], new byte[0], "");
        }
    }

    public static final CustomPacketPayload.Type<S2CLibraryPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(RedstonePcbs.MOD_ID, "library_sync"));

    public static final StreamCodec<ByteBuf, S2CLibraryPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeBoolean(payload.canSave());
                buf.writeBoolean(payload.canImport());
                buf.writeInt(payload.limit());
                buf.writeInt(payload.designs().size());
                for (Design design : payload.designs()) {
                    ByteBufCodecs.STRING_UTF8.encode(buf, design.name());
                    ByteBufCodecs.BYTE_ARRAY.encode(buf, design.data());
                    ByteBufCodecs.BYTE_ARRAY.encode(buf, design.faces());
                    ByteBufCodecs.BYTE_ARRAY.encode(buf, design.ports());
                    ByteBufCodecs.STRING_UTF8.encode(buf, design.author());
                }
                buf.writeInt(payload.players().size());
                for (String player : payload.players()) {
                    ByteBufCodecs.STRING_UTF8.encode(buf, player);
                }
            },
            buf -> {
                boolean canSave = buf.readBoolean();
                boolean canImport = buf.readBoolean();
                int limit = buf.readInt();
                int count = buf.readInt();
                List<Design> designs = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    String name = ByteBufCodecs.STRING_UTF8.decode(buf);
                    byte[] data = ByteBufCodecs.BYTE_ARRAY.decode(buf);
                    byte[] faces = ByteBufCodecs.BYTE_ARRAY.decode(buf);
                    byte[] ports = ByteBufCodecs.BYTE_ARRAY.decode(buf);
                    String author = ByteBufCodecs.STRING_UTF8.decode(buf);
                    designs.add(new Design(name, data, faces, ports, author));
                }
                int playerCount = buf.readInt();
                List<String> players = new ArrayList<>(playerCount);
                for (int i = 0; i < playerCount; i++) {
                    players.add(ByteBufCodecs.STRING_UTF8.decode(buf));
                }
                return new S2CLibraryPayload(designs, canSave, limit, canImport, players);
            });

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
