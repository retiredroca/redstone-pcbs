package com.retiredroca.redstonepcbs.net;

import com.retiredroca.redstonepcbs.RedstonePcbs;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server -> client instruction to open the circuit editor for a board (block or item). */
public record S2COpenEditorPayload(int kind, BlockPos pos, int slot, int face) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<S2COpenEditorPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(RedstonePcbs.MOD_ID, "open_editor"));

    public static final StreamCodec<ByteBuf, S2COpenEditorPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, S2COpenEditorPayload::kind,
            BlockPos.STREAM_CODEC, S2COpenEditorPayload::pos,
            ByteBufCodecs.VAR_INT, S2COpenEditorPayload::slot,
            ByteBufCodecs.VAR_INT, S2COpenEditorPayload::face,
            S2COpenEditorPayload::new);

    public static S2COpenEditorPayload block(BlockPos pos, int face) {
        return new S2COpenEditorPayload(C2SEditPayload.KIND_BLOCK, pos, 0, face);
    }

    public static S2COpenEditorPayload item(int slot, int face) {
        return new S2COpenEditorPayload(C2SEditPayload.KIND_ITEM, BlockPos.ZERO, slot, face);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
