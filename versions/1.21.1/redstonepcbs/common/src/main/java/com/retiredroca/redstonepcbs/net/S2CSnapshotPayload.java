package com.retiredroca.redstonepcbs.net;

import com.retiredroca.redstonepcbs.RedstonePcbs;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server -> client full board snapshot, sent on open and after every accepted edit. */
public record S2CSnapshotPayload(int kind, BlockPos pos, int slot, byte[] data) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<S2CSnapshotPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(RedstonePcbs.MOD_ID, "snapshot"));

    public static final StreamCodec<ByteBuf, S2CSnapshotPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, S2CSnapshotPayload::kind,
            BlockPos.STREAM_CODEC, S2CSnapshotPayload::pos,
            ByteBufCodecs.VAR_INT, S2CSnapshotPayload::slot,
            ByteBufCodecs.BYTE_ARRAY, S2CSnapshotPayload::data,
            S2CSnapshotPayload::new);

    public static S2CSnapshotPayload block(BlockPos pos, byte[] data) {
        return new S2CSnapshotPayload(C2SEditPayload.KIND_BLOCK, pos, 0, data);
    }

    public static S2CSnapshotPayload item(int slot, byte[] data) {
        return new S2CSnapshotPayload(C2SEditPayload.KIND_ITEM, BlockPos.ZERO, slot, data);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
