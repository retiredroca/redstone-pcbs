package com.retiredroca.redstonepcbs.net;

import com.retiredroca.redstonepcbs.RedstonePcbs;
import com.retiredroca.redstonepcbs.chip.Dir;
import com.retiredroca.redstonepcbs.chip.PortFlow;
import com.retiredroca.redstonepcbs.chip.PortLink;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import org.jetbrains.annotations.Nullable;

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
    /** Place an arbitrary item: {@code text} is the item registry id, {@code packed} packs the facing. */
    public static final int ACTION_PLACE = 15;
    /** Assign/clear a gateway face: {@code index} is the cell, the packed low byte is the face or 0xFF. */
    public static final int ACTION_SET_FACE = 16;
    /**
     * Assign/clear a redstone port: {@code index} is the cell and {@code packed} holds face+1 in bits
     * 0-2, side+1 in bits 3-5 and the flow in bit 6, or 0xFF to clear. The two +1 offsets leave zero
     * meaning "unset", so a half-written value cannot invent a bridge.
     */
    public static final int ACTION_SET_PORT = 17;

    /** {@code packed} value meaning "no port". */
    public static final int PACKED_NONE = 0xFF;
    private static final int PORT_FACE_SHIFT = 0;
    private static final int PORT_SIDE_SHIFT = 3;
    private static final int PORT_FLOW_SHIFT = 6;
    private static final int PORT_DIR_MASK = 0x7;

    /** Packs a port into the wire form. */
    public static int packPort(Dir face, Dir side, PortFlow flow) {
        return (face.ordinal() + 1) | ((side.ordinal() + 1) << PORT_SIDE_SHIFT)
                | (flow.ordinal() << PORT_FLOW_SHIFT);
    }

    /** Reads a packed port, or {@code null} when it is cleared or malformed. */
    @Nullable
    public static PortLink unpackPort(int packed) {
        if (packed == PACKED_NONE) {
            return null;
        }
        int face = (packed >>> PORT_FACE_SHIFT) & PORT_DIR_MASK;
        int side = (packed >>> PORT_SIDE_SHIFT) & PORT_DIR_MASK;
        int flow = (packed >>> PORT_FLOW_SHIFT) & 0x1;
        if (face == 0 || side == 0) {
            return null;
        }
        return new PortLink(Dir.byOrdinal(face - 1), Dir.byOrdinal(side - 1), PortFlow.byOrdinal(flow));
    }

    public static final int FLAG_SUBTRACT = 1;
    /** Set on a port assignment the player confirmed after being warned it displaces another cell. */
    public static final int FLAG_TAKE_OVER = 2;

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

    /** Assigns/clears a redstone port on a placed board. */
    public static C2SEditPayload port(BlockPos pos, int index, int packed, int flags) {
        return new C2SEditPayload(KIND_BLOCK, pos, 0, ACTION_SET_PORT, index,
                packed | ((flags & 0xFF) << 16), "");
    }

    /** Assigns/clears a redstone port on a portable board. */
    public static C2SEditPayload portItem(int slot, int index, int packed, int flags) {
        return new C2SEditPayload(KIND_ITEM, BlockPos.ZERO, slot, ACTION_SET_PORT, index,
                packed | ((flags & 0xFF) << 16), "");
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
