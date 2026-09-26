package com.retiredroca.redstonepcbs.net;

import com.retiredroca.redstonepcbs.RedstonePcbs;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client -> server request against the player's saved-designs library. */
public record C2SLibraryPayload(int kind, int slot, BlockPos pos, int action, int index, String name,
        byte[] data, byte[] faces, byte[] ports) implements CustomPacketPayload {

    public C2SLibraryPayload(int kind, int slot, BlockPos pos, int action, int index, String name,
            byte[] data) {
        this(kind, slot, pos, action, index, name, data, EMPTY, EMPTY);
    }

    public C2SLibraryPayload(int kind, int slot, BlockPos pos, int action, int index, String name,
            byte[] data, byte[] faces) {
        this(kind, slot, pos, action, index, name, data, faces, EMPTY);
    }

    public static final int ACTION_LIST = 0;
    public static final int ACTION_SAVE = 1;
    public static final int ACTION_APPLY = 2;
    public static final int ACTION_DELETE = 3;
    public static final int ACTION_IMPORT = 4;
    /** Share a saved design into another online player's library. */
    public static final int ACTION_SHARE = 5;

    private static final byte[] EMPTY = new byte[0];

    public static final CustomPacketPayload.Type<C2SLibraryPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(RedstonePcbs.MOD_ID, "library"));

    public static final StreamCodec<ByteBuf, C2SLibraryPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                ByteBufCodecs.VAR_INT.encode(buf, payload.kind());
                ByteBufCodecs.VAR_INT.encode(buf, payload.slot());
                BlockPos.STREAM_CODEC.encode(buf, payload.pos());
                ByteBufCodecs.VAR_INT.encode(buf, payload.action());
                ByteBufCodecs.VAR_INT.encode(buf, payload.index());
                ByteBufCodecs.STRING_UTF8.encode(buf, payload.name());
                ByteBufCodecs.BYTE_ARRAY.encode(buf, payload.data());
                ByteBufCodecs.BYTE_ARRAY.encode(buf, payload.faces());
                ByteBufCodecs.BYTE_ARRAY.encode(buf, payload.ports());
            },
            buf -> new C2SLibraryPayload(
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    BlockPos.STREAM_CODEC.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    ByteBufCodecs.BYTE_ARRAY.decode(buf),
                    ByteBufCodecs.BYTE_ARRAY.decode(buf)));

    public static C2SLibraryPayload block(BlockPos pos, int action, int index) {
        return block(pos, action, index, "");
    }

    public static C2SLibraryPayload block(BlockPos pos, int action, int index, String name) {
        return new C2SLibraryPayload(C2SEditPayload.KIND_BLOCK, 0, pos, action, index, name, EMPTY);
    }

    public static C2SLibraryPayload item(int slot, int action, int index) {
        return item(slot, action, index, "");
    }

    public static C2SLibraryPayload item(int slot, int action, int index, String name) {
        return new C2SLibraryPayload(C2SEditPayload.KIND_ITEM, slot, BlockPos.ZERO, action, index, name, EMPTY);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
