package com.frameflags.tpa_test;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@Mod(TpaProbeNeoForgeMod.MODID)
public final class TpaProbeNeoForgeMod {
    public static final String MODID = "tpa_test";

    public TpaProbeNeoForgeMod(IEventBus modEventBus) {
        TpaProbe.bootstrap("26.3-neoforge", FMLPaths.GAMEDIR.get());
        NeoForge.EVENT_BUS.addListener(this::onJoin);
        NeoForge.EVENT_BUS.addListener(this::onTick);
    }

    private void onJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            net.minecraft.server.MinecraftServer server = player.level().getServer();
            if (server != null) TpaProbe.onJoin(player, server.getTickCount());
        }
    }

    private void onTick(ServerTickEvent.Post event) {
        TpaProbe.onTick(event.getServer(), event.getServer().getTickCount());
    }
}
