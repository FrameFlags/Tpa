package com.frameflags.tpa;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@Mod(TpaNeoForgeMod.MODID)
public final class TpaNeoForgeMod {
    public static final String MODID = "tpa";

    public TpaNeoForgeMod(IEventBus modEventBus) {
        NeoForge.EVENT_BUS.addListener(this::onServerStarting);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
        NeoForge.EVENT_BUS.addListener(this::onLogout);
        NeoForge.EVENT_BUS.addListener(this::onDeath);
        NeoForge.EVENT_BUS.addListener(this::onServerStopped);
    }

    private void onServerStarting(ServerStartingEvent event) {
        Tpa.init(FMLPaths.GAMEDIR.get());
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        TpaCommands.register(event.getDispatcher());
    }

    private void onServerTick(ServerTickEvent.Post event) {
        Tpa.onServerTick(event.getServer(), event.getServer().getTickCount());
    }

    private void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        Tpa.purge(event.getEntity().getUUID());
    }

    private void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            Tpa.purge(player.getUUID());
        }
    }

    private void onServerStopped(ServerStoppedEvent event) {
        Tpa.clear();
    }
}
