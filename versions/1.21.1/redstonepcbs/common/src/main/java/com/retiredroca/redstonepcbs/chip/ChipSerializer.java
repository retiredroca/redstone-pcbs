package com.retiredroca.redstonepcbs.chip;

/**
 * Compact, versioned binary serialization for a {@link ChipWorld}.
 *
 * <pre>
 *   v5 header: version, sizeX, sizeY, sizeZ, flags(bit0 = filter hopper mode)
 *   per cell (4 bytes):
 *     byte 0: part ordinal (low 5 bits) | facing ordinal (high 3 bits)
 *     byte 1: signal power/strength (low nibble) | delay (high nibble)
 *     byte 2: flags - bit0 powered, bit1 subtract, bit2 on, bit3 locked | dust sides (high nibble)
 *     byte 3: dust climb sides (low nibble) | container analog output (high nibble)
 * </pre>
 *
 * <p>v1-v3 used 3 bytes per cell with the part in the low nibble (at most 16 parts). v4 widened the
 * part field to 5 bits and added the per-cell container analog value. v5 packs the dust "climb"
 * sides into byte 3 (v4 stored the analog in the low nibble there).
 *
 * <p>Derived state (a solid block's weak power, pending delays, observer watch state) is not stored;
 * it is recomputed on the first tick after loading. Dust shapes are re-derived after loading too.
 */
public final class ChipSerializer {
    private ChipSerializer() {}

    public static byte[] write(ChipWorld world) {
        int sizeX = world.sizeX();
        int sizeY = world.sizeY();
        int sizeZ = world.sizeZ();
        byte[] data = new byte[5 + world.cellCount() * 4];
        data[0] = (byte) ChipWorld.FORMAT_VERSION;
        data[1] = (byte) sizeX;
        data[2] = (byte) sizeY;
        data[3] = (byte) sizeZ;
        data[4] = (byte) (world.isFilterHopperMode() ? 1 : 0);
        int p = 5;
        for (int i = 0; i < world.cellCount(); i++) {
            Cell c = world.cell(i);
            data[p++] = (byte) ((c.part.ordinal() & 0x1F) | ((c.facing.ordinal() & 0x07) << 5));
            data[p++] = (byte) ((c.power & 0x0F) | ((c.delay & 0x0F) << 4));
            int flags = (c.powered ? 1 : 0)
                    | (c.subtract ? 2 : 0)
                    | (c.on ? 4 : 0)
                    | (c.locked ? 8 : 0)
                    | ((c.dustMask & 0x0F) << 4);
            data[p++] = (byte) flags;
            data[p++] = (byte) ((c.dustUpMask & 0x0F) | ((c.analog & 0x0F) << 4));
        }
        return data;
    }

    public static ChipWorld read(byte[] data) {
        if (data == null || data.length < 4) {
            return new ChipWorld();
        }
        int version = data[0] & 0xFF;
        if (version < 1 || version > ChipWorld.FORMAT_VERSION) {
            return new ChipWorld();
        }
        int sizeX = data[1] & 0xFF;
        int sizeY = data[2] & 0xFF;
        int sizeZ = data[3] & 0xFF;
        ChipWorld world = new ChipWorld(sizeX, sizeY, sizeZ);
        int p;
        if (version >= 2) {
            if (data.length < 5) {
                return world;
            }
            world.setFilterHopperMode((data[4] & 1) != 0);
            p = 5;
        } else {
            p = 4;
        }
        int cellBytes = version >= 4 ? 4 : 3;
        if (data.length < p + world.cellCount() * cellBytes) {
            return world;
        }
        for (int i = 0; i < world.cellCount(); i++) {
            int b0 = data[p++] & 0xFF;
            int b1 = data[p++] & 0xFF;
            int b2 = data[p++] & 0xFF;
            Cell c = world.cell(i);
            if (version >= 4) {
                c.part = Part.byOrdinal(b0 & 0x1F);
                c.facing = Dir.byOrdinal((b0 >> 5) & 0x07);
                int b3 = data[p++] & 0xFF;
                if (version >= 5) {
                    c.dustUpMask = b3 & 0x0F;
                    c.analog = (b3 >> 4) & 0x0F;
                } else {
                    c.analog = b3 & 0x0F;
                }
            } else {
                c.part = Part.byOrdinal(b0 & 0x0F);
                c.facing = Dir.byOrdinal((b0 >> 4) & 0x07);
            }
            c.power = b1 & 0x0F;
            c.delay = (b1 >> 4) & 0x0F;
            c.powered = (b2 & 1) != 0;
            c.subtract = (b2 & 2) != 0;
            c.on = (b2 & 4) != 0;
            c.locked = (b2 & 8) != 0;
            c.dustMask = version >= 3 ? (b2 >> 4) & 0x0F : 0x0F;
        }
        world.refreshAllDustShapes();
        world.markDirty();
        return world;
    }

    public static byte[] empty() {
        return write(new ChipWorld());
    }
}
