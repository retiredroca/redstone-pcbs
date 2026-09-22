package com.retiredroca.redstonepcbs.data;

import com.mojang.serialization.Codec;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.nio.ByteBuffer;

/**
 * The serialized chip payload carried by blueprints (and available for future item-borne boards).
 * Wraps the raw bytes produced by {@code ChipSerializer} so it can be a data component.
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
}
