package com.retiredroca.redstonepcbs.chip;

/**
 * Compact, versioned binary serialization for a {@link ChipWorld}.
 *
 * <pre>
 *   v2 header: version, sizeX, sizeY, sizeZ, flags(bit0 = simple hopper mode)
 *   per cell (3 bytes):
 *     byte 0: part ordinal (low nibble) | facing ordinal (high nibble)
 *     byte 1: signal power/strength (low nibble) | delay (high nibble)
 *     byte 2: flags - bit0 powered, bit1 subtract, bit2 on, bit3 locked
 * </pre>
 *
 * <p>Derived state (a solid block's weak power, pending delays, observer watch state) is not stored;
 * it is recomputed on the first tick after loading.
 */
public final class ChipSerializer {
    private ChipSerializer() {}

    public static byte[] write(ChipWorld world) {
        int sizeX = world.sizeX();
        int sizeY = world.sizeY();
        int sizeZ = world.sizeZ();
        byte[] data = new byte[5 + world.cellCount() * 3];
        data[0] = (byte) ChipWorld.FORMAT_VERSION;
        data[1] = (byte) sizeX;
        data[2] = (byte) sizeY;
        data[3] = (byte) sizeZ;
        data[4] = (byte) (world.isSimpleHopperMode() ? 1 : 0);
        int p = 5;
        for (int i = 0; i < world.cellCount(); i++) {
            Cell c = world.cell(i);
            data[p++] = (byte) (c.part.ordinal() | (c.facing.ordinal() << 4));
            data[p++] = (byte) ((c.power & 0x0F) | ((c.delay & 0x0F) << 4));
            int flags = (c.powered ? 1 : 0)
                    | (c.subtract ? 2 : 0)
                    | (c.on ? 4 : 0)
                    | (c.locked ? 8 : 0)
                    | ((c.dustMask & 0x0F) << 4);
            data[p++] = (byte) flags;
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
            world.setSimpleHopperMode((data[4] & 1) != 0);
            p = 5;
        } else {
            p = 4;
        }
        if (data.length < p + world.cellCount() * 3) {
            return world;
        }
        for (int i = 0; i < world.cellCount(); i++) {
            int b0 = data[p++] & 0xFF;
            int b1 = data[p++] & 0xFF;
            int b2 = data[p++] & 0xFF;
            Cell c = world.cell(i);
            c.part = Part.byOrdinal(b0 & 0x0F);
            c.facing = Dir.byOrdinal((b0 >> 4) & 0x07);
            c.power = b1 & 0x0F;
            c.delay = (b1 >> 4) & 0x0F;
            c.powered = (b2 & 1) != 0;
            c.subtract = (b2 & 2) != 0;
            c.on = (b2 & 4) != 0;
            c.locked = (b2 & 8) != 0;
            c.dustMask = version >= 3 ? (b2 >> 4) & 0x0F : 0x0F;
        }
        world.markDirty();
        return world;
    }

    public static byte[] empty() {
        return write(new ChipWorld());
    }
}
