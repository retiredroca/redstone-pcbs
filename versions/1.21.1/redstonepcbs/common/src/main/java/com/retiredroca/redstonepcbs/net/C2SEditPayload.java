package com.retiredroca.redstonepcbs.net;

import com.retiredroca.redstonepcbs.RedstonePcbs;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client -> server edit request for a board, either a placed block or a PCB item in an inventory slot. */
public record C2SEditPayload(int kind, BlockPos pos, int slot, int action, int index, int packed, String text)
        implements CustomPacketPayload {

    public static final int KIND_BLOCK = 0;
    public static final int KIND_ITEM = 1;

    public static final int ACTION_SET = 0;
    public static final int ACTION_CLEAR = 1;
    public static final int ACTION_INTERACT = 2;
    public static final int ACTION_ROTATE = 3;
    public static final int ACTION_CYCLE_DELAY = 4;
    public static final int ACTION_TOGGLE_MODE = 5;
    public static final int ACTION_CLEAR_ALL = 6;
    public static final int ACTION_REQUEST = 7;
    public static final int ACTION_OPEN_UI = 11;
    public static final int ACTION_PULSE_LAYER = 12;
    public static final int ACTION_TOGGLE_INPUT = 13;
    public static final int ACTION_TOGGLE_OUTPUT = 14;
    /** Place an arbitrary item: {@code text} is the item registry id, {@code packed} packs the facing. */
    public static final int ACTION_PLACE = 15;
    /** Assign/clear a gateway face: {@code index} is the cell, the packed low byte is the face or 0xFF. */
    public static final int ACTION_SET_FACE = 16;

    public static final int FLAG_SUBTRACT = 1;

    public static final CustomPacketPayload.Type<C2SEditPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(RedstonePcbs.MOD_ID, "edit"));

    public static final StreamCodec<ByteBuf, C2SEditPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public C2SEditPayload decode(ByteBuf buf) {
            int kind = ByteBufCodecs.VAR_INT.decode(buf);
            BlockPos pos = BlockPos.STREAM_CODEC.decode(buf);
            int slot = ByteBufCodecs.VAR_INT.decode(buf);
            int action = ByteBufCodecs.VAR_INT.decode(buf);
            int index = ByteBufCodecs.VAR_INT.decode(buf);
            int packed = ByteBufCodecs.VAR_INT.decode(buf);
            String text = ByteBufCodecs.STRING_UTF8.decode(buf);
            return new C2SEditPayload(kind, pos, slot, action, index, packed, text);
        }

        @Override
        public void encode(ByteBuf buf, C2SEditPayload value) {
            ByteBufCodecs.VAR_INT.encode(buf, value.kind());
            BlockPos.STREAM_CODEC.encode(buf, value.pos());
            ByteBufCodecs.VAR_INT.encode(buf, value.slot());
            ByteBufCodecs.VAR_INT.encode(buf, value.action());
            ByteBufCodecs.VAR_INT.encode(buf, value.index());
            ByteBufCodecs.VAR_INT.encode(buf, value.packed());
            ByteBufCodecs.STRING_UTF8.encode(buf, value.text());
        }
    };

    public static C2SEditPayload block(BlockPos pos, int action, int index, int part, int facing, int flags) {
        return new C2SEditPayload(KIND_BLOCK, pos, 0, action, index, pack(part, facing, flags), "");
    }

    public static C2SEditPayload item(int slot, int action, int index, int part, int facing, int flags) {
        return new C2SEditPayload(KIND_ITEM, BlockPos.ZERO, slot, action, index, pack(part, facing, flags), "");
    }

    /** Place action: {@code text} is the item registry id, {@code packed} packs the facing (bits 8-15). */
    public static C2SEditPayload place(BlockPos pos, int index, String itemId, int facing, int flags) {
        return new C2SEditPayload(KIND_BLOCK, pos, 0, ACTION_PLACE, index,
                (facing & 0xFF) << 8 | (flags & 0xFF) << 16, itemId);
    }

    public static C2SEditPayload placeItem(int slot, int index, String itemId, int facing, int flags) {
        return new C2SEditPayload(KIND_ITEM, BlockPos.ZERO, slot, ACTION_PLACE, index,
                (facing & 0xFF) << 8 | (flags & 0xFF) << 16, itemId);
    }

    /** Face action: {@code index} is the cell, the packed low byte is the target face ordinal (0xFF = none). */
    public static C2SEditPayload face(BlockPos pos, int index, int face) {
        return new C2SEditPayload(KIND_BLOCK, pos, 0, ACTION_SET_FACE, index, face & 0xFF, "");
    }

    /** Face action for a portable board item. */
    public static C2SEditPayload faceItem(int slot, int index, int face) {
        return new C2SEditPayload(KIND_ITEM, BlockPos.ZERO, slot, ACTION_SET_FACE, index, face & 0xFF, "");
    }

    public int part() {
        return packed & 0xFF;
    }

    public int facing() {
        return (packed >> 8) & 0xFF;
    }

    public int flags() {
        return (packed >> 16) & 0xFF;
    }

    private static int pack(int part, int facing, int flags) {
        return (part & 0xFF) | ((facing & 0xFF) << 8) | ((flags & 0xFF) << 16);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
