package com.frameflags.tpa_test;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

/** Test-only entrypoint: drives the tick-based TPA probe without touching product classes. */
public final class TpaTestMod implements ModInitializer {
    @Override
    public void onInitialize() {
        TpaProbe.init();
        ServerTickEvents.END_SERVER_TICK.register(server -> TpaProbe.onTick(server, server.getTicks()));
    }
}
