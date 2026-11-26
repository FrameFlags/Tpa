package com.frameflags.tpa_test;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

public final class TpaProbeFabricMod implements ModInitializer {
    @Override
    public void onInitialize() {
        TpaProbe.bootstrap("1.20.1-fabric");
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                TpaProbe.onJoin(handler.getPlayer(), server.getTickCount()));
        ServerTickEvents.END_SERVER_TICK.register(server ->
                TpaProbe.onTick(server, server.getTickCount()));
    }
}
