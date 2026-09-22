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
public record S2CLibraryPayload(List<Design> designs, boolean canSave) implements CustomPacketPayload {
    /** A saved design: a display name and the serialized chip. */
    public record Design(String name, byte[] data) {}

    public static final CustomPacketPayload.Type<S2CLibraryPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(RedstonePcbs.MOD_ID, "library_sync"));

    public static final StreamCodec<ByteBuf, S2CLibraryPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeBoolean(payload.canSave());
                buf.writeInt(payload.designs().size());
                for (Design design : payload.designs()) {
                    ByteBufCodecs.STRING_UTF8.encode(buf, design.name());
                    ByteBufCodecs.BYTE_ARRAY.encode(buf, design.data());
                }
            },
            buf -> {
                boolean canSave = buf.readBoolean();
                int count = buf.readInt();
                List<Design> designs = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    String name = ByteBufCodecs.STRING_UTF8.decode(buf);
                    byte[] data = ByteBufCodecs.BYTE_ARRAY.decode(buf);
                    designs.add(new Design(name, data));
                }
                return new S2CLibraryPayload(designs, canSave);
            });

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
