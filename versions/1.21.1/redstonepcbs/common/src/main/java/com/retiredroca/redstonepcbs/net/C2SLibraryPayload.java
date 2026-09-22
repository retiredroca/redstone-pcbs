package com.retiredroca.redstonepcbs.net;

import com.retiredroca.redstonepcbs.RedstonePcbs;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client -> server request against the player's saved-designs library. */
public record C2SLibraryPayload(int kind, int slot, net.minecraft.core.BlockPos pos, int action, int index)
        implements CustomPacketPayload {

    public static final int ACTION_LIST = 0;
    public static final int ACTION_SAVE = 1;
    public static final int ACTION_APPLY = 2;
    public static final int ACTION_DELETE = 3;

    public static final CustomPacketPayload.Type<C2SLibraryPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(RedstonePcbs.MOD_ID, "library"));

    public static final StreamCodec<ByteBuf, C2SLibraryPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, C2SLibraryPayload::kind,
            ByteBufCodecs.VAR_INT, C2SLibraryPayload::slot,
            net.minecraft.core.BlockPos.STREAM_CODEC, C2SLibraryPayload::pos,
            ByteBufCodecs.VAR_INT, C2SLibraryPayload::action,
            ByteBufCodecs.VAR_INT, C2SLibraryPayload::index,
            C2SLibraryPayload::new);

    public static C2SLibraryPayload block(net.minecraft.core.BlockPos pos, int action, int index) {
        return new C2SLibraryPayload(C2SEditPayload.KIND_BLOCK, 0, pos, action, index);
    }

    public static C2SLibraryPayload item(int slot, int action, int index) {
        return new C2SLibraryPayload(C2SEditPayload.KIND_ITEM, slot, net.minecraft.core.BlockPos.ZERO, action,
                index);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
