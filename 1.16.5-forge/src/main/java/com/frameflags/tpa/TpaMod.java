package com.frameflags.tpa;

import com.frameflags.tpa.core.TpaRequest;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.server.FMLServerStartedEvent;
import net.minecraftforge.fml.event.server.FMLServerStoppedEvent;

import java.util.UUID;

/** Forge 1.16.5 entry point: init, commands, expiry tick and lifecycle purge. */
@Mod("tpa")
public final class TpaMod {
    private MinecraftServer server;

    public TpaMod() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onServerStarted(FMLServerStartedEvent event) {
        server = event.getServer();
        Tpa.init(server.getServerDirectory().toPath());
        Tpa.clear();
    }

    @SubscribeEvent
    public void onServerStopped(FMLServerStoppedEvent event) {
        Tpa.clear();
        server = null;
    }

    @SubscribeEvent
    public void registerCommands(RegisterCommandsEvent event) {
        TpaCommands.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || server == null) return;
        int tick = (int) Tpa.tick();
        for (TpaRequest due : Tpa.advanceTick()) {
            String who = due.targetName == null || due.targetName.isEmpty() ? "a player" : due.targetName;
            Tpa.message(Tpa.findPlayer(server, due.requester), "Your teleport request to " + who + " expired.", tick);
            Tpa.message(Tpa.findPlayer(server, due.target), "The teleport request from " + who + " expired.", tick);
        }
    }

    @SubscribeEvent
    public void onDeath(LivingDeathEvent event) {
        if (event.getEntityLiving() instanceof ServerPlayerEntity) purge(event.getEntityLiving().getUUID());
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        purge(event.getPlayer().getUUID());
    }

    private static void purge(UUID uuid) {
        if (uuid != null) Tpa.purge(uuid);
    }
}
