package com.retiredroca.redstonepcbs.net;

import com.retiredroca.redstonepcbs.RedstonePcbs;
import com.retiredroca.redstonepcbs.block.PcbAttach;
import com.retiredroca.redstonepcbs.chip.PortCodec;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server -&gt; client full board snapshot, sent on open and after every accepted edit. {@code faces}
 * carries the per-cell gateway attachments (see {@link com.retiredroca.redstonepcbs.block.PcbAttach})
 * and {@code ports} the per-cell redstone ports; each is empty when there are none.
 */
public record S2CSnapshotPayload(int kind, BlockPos pos, int slot, byte[] data, byte[] faces, byte[] ports)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<S2CSnapshotPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(RedstonePcbs.MOD_ID, "snapshot"));

    public static final StreamCodec<ByteBuf, S2CSnapshotPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, S2CSnapshotPayload::kind,
            BlockPos.STREAM_CODEC, S2CSnapshotPayload::pos,
            ByteBufCodecs.VAR_INT, S2CSnapshotPayload::slot,
            ByteBufCodecs.BYTE_ARRAY, S2CSnapshotPayload::data,
            ByteBufCodecs.BYTE_ARRAY, S2CSnapshotPayload::faces,
            ByteBufCodecs.BYTE_ARRAY, S2CSnapshotPayload::ports,
            S2CSnapshotPayload::new);

    public static S2CSnapshotPayload block(BlockPos pos, byte[] data) {
        return block(pos, data, PcbAttach.EMPTY);
    }

    public static S2CSnapshotPayload block(BlockPos pos, byte[] data, byte[] faces) {
        return block(pos, data, faces, PortCodec.EMPTY);
    }

    public static S2CSnapshotPayload block(BlockPos pos, byte[] data, byte[] faces, byte[] ports) {
        return new S2CSnapshotPayload(C2SEditPayload.KIND_BLOCK, pos, 0, data, faces, ports);
    }

    public static S2CSnapshotPayload item(int slot, byte[] data) {
        return item(slot, data, PcbAttach.EMPTY);
    }

    public static S2CSnapshotPayload item(int slot, byte[] data, byte[] faces) {
        return item(slot, data, faces, PortCodec.EMPTY);
    }

    public static S2CSnapshotPayload item(int slot, byte[] data, byte[] faces, byte[] ports) {
        return new S2CSnapshotPayload(C2SEditPayload.KIND_ITEM, BlockPos.ZERO, slot, data, faces, ports);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
