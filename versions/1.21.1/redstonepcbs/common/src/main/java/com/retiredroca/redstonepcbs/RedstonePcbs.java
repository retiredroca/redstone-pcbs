package com.retiredroca.redstonepcbs;

import com.retiredroca.redstonepcbs.platform.RedstonePcbsPlatform;

/**
 * Loader-neutral entry point. Each loader's entrypoint installs its own {@link RedstonePcbsPlatform}
 * implementation; the shared code then uses {@link #platform()} for loader-specific operations.
 *
 * <p>This class lives in the shared {@code common/} sources, which are relocated per loader (for
 * example {@code com.retiredroca.redstonepcbs.fabric.common}) so one universal jar can carry both
 * mapping variants. It therefore must never reference loader APIs directly.
 */
public final class RedstonePcbs {
    public static final String MOD_ID = "redstonepcbs";

    private static RedstonePcbsPlatform platform;

    private RedstonePcbs() {}

    public static void setPlatform(RedstonePcbsPlatform platform) {
        RedstonePcbs.platform = platform;
    }

    public static RedstonePcbsPlatform platform() {
        if (platform == null) {
            throw new IllegalStateException("Redstone PCBs platform not set - was a loader entrypoint loaded?");
        }
        return platform;
    }
}
