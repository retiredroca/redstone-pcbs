package com.retiredroca.redstonepcbs.data;

import com.mojang.serialization.Codec;
import com.retiredroca.redstonepcbs.chip.ChipLayout;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.nio.ByteBuffer;

/**
 * The serialized chip payload carried by a PCB item (and blueprints). Holds the grid bytes plus the
 * gateway attachment bytes and the redstone port bytes, so a portable board keeps which cells are
 * exposed on which PCB face and which cells bridge a redstone port.
 *
 * <p>The byte layout lives in {@link ChipLayout}, which also reads payloads written before ports
 * existed; that one implementation is unit tested in {@code enginetest}.
 */
public record ChipData(byte[] data) {
    public static final Codec<ChipData> CODEC = Codec.BYTE_BUFFER.xmap(
            buffer -> {
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                return new ChipData(bytes);
            },
            chip -> ByteBuffer.wrap(chip.data));

    public static final StreamCodec<ByteBuf, ChipData> STREAM_CODEC =
            ByteBufCodecs.BYTE_ARRAY.map(ChipData::new, ChipData::data);

    /** Packs the sections into one payload. */
    public static byte[] pack(byte[] faces, byte[] ports, byte[] grid) {
        return ChipLayout.pack(faces, ports, grid);
    }

    /** The gateway attachment bytes from a packed payload (empty when none). */
    public byte[] faces() {
        return ChipLayout.faces(data);
    }

    /** The redstone port bytes from a packed payload (empty when none). */
    public byte[] ports() {
        return ChipLayout.ports(data);
    }

    /** The grid bytes from a packed payload. Tolerates a bare grid (no header) for old saves. */
    public byte[] grid() {
        return ChipLayout.grid(data);
    }
}
