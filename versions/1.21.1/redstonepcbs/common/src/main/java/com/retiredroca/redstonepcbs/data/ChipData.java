package com.retiredroca.redstonepcbs.data;

import com.mojang.serialization.Codec;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.nio.ByteBuffer;

/**
 * The serialized chip payload carried by a PCB item (and blueprints). Holds the grid bytes plus the
 * gateway attachment bytes, so a portable board keeps which cells are exposed on which PCB face. The
 * two are packed as {@code [facesLen][faces...][grid...]} so the format stays a single byte array.
 */
public record ChipData(byte[] data) {
    public static final ChipData EMPTY = new ChipData(new byte[0]);

    public static final Codec<ChipData> CODEC = Codec.BYTE_BUFFER.xmap(
            buffer -> {
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                return new ChipData(bytes);
            },
            chip -> ByteBuffer.wrap(chip.data));

    public static final StreamCodec<ByteBuf, ChipData> STREAM_CODEC =
            ByteBufCodecs.BYTE_ARRAY.map(ChipData::new, ChipData::data);

    /** Packs {@code faces} and {@code grid} into one payload: {@code [facesLen][faces][grid]}. */
    public static byte[] pack(byte[] faces, byte[] grid) {
        byte[] f = faces == null ? new byte[0] : faces;
        byte[] g = grid == null ? new byte[0] : grid;
        byte[] out = new byte[4 + f.length + g.length];
        out[0] = (byte) (f.length & 0xFF);
        out[1] = (byte) ((f.length >> 8) & 0xFF);
        out[2] = (byte) ((f.length >> 16) & 0xFF);
        out[3] = (byte) ((f.length >> 24) & 0xFF);
        System.arraycopy(f, 0, out, 4, f.length);
        System.arraycopy(g, 0, out, 4 + f.length, g.length);
        return out;
    }

    /** The gateway attachment bytes from a packed payload (empty when absent). */
    public byte[] faces() {
        if (data.length < 4) {
            return new byte[0];
        }
        int len = (data[0] & 0xFF) | ((data[1] & 0xFF) << 8) | ((data[2] & 0xFF) << 16)
                | ((data[3] & 0xFF) << 24);
        if (len <= 0 || len > data.length - 4) {
            return new byte[0];
        }
        byte[] out = new byte[len];
        System.arraycopy(data, 4, out, 0, len);
        return out;
    }

    /** The grid bytes from a packed payload. Tolerates a bare grid (no header) for old saves. */
    public byte[] grid() {
        if (data.length < 4) {
            return data;
        }
        int len = (data[0] & 0xFF) | ((data[1] & 0xFF) << 8) | ((data[2] & 0xFF) << 16)
                | ((data[3] & 0xFF) << 24);
        if (len < 0 || len > data.length - 4) {
            return data;
        }
        byte[] out = new byte[data.length - 4 - len];
        System.arraycopy(data, 4 + len, out, 0, out.length);
        return out;
    }
}
