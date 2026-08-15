package xyz.pyrehaven.omwh;

import net.fabricmc.api.ModInitializer;

/** Fabric entrypoint reserved for OMWH's server composition root. */
public final class Omwh implements ModInitializer {
    @Override
    public void onInitialize() {
        throw new IllegalStateException("OMWH structural groundwork contains no gameplay implementation");
    }
}
