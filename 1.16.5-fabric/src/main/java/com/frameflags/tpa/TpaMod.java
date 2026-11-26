package com.frameflags.tpa;

import com.frameflags.tpa.core.TpaRequest;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.UUID;

/** Fabric 1.16.5 entry point: init, commands, expiry tick and lifecycle purge. */
public final class TpaMod implements ModInitializer {
    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            Tpa.init(FabricLoader.getInstance().getGameDir());
            Tpa.clear();
        });
        CommandRegistrationCallback.EVENT.register(TpaCommands::register);
        ServerTickEvents.END_SERVER_TICK.register(TpaMod::onServerTick);

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayerEntity player = handler.player;
            if (player != null) purge(player.getUuid());
        });
        ServerPlayerEvents.ALLOW_DEATH.register((player, damageSource, damageTaken) -> {
            purge(player.getUuid());
            return true;
        });
    }

    private static void onServerTick(MinecraftServer server) {
        for (TpaRequest due : Tpa.advanceTick()) {
            String who = due.targetName == null || due.targetName.isEmpty() ? "a player" : due.targetName;
            TpaCommands.notify(due.requester, server, "Your teleport request to " + who + " expired.");
            TpaCommands.notify(due.target, server, "The teleport request from " + who + " expired.");
        }
    }

    private static void purge(UUID uuid) {
        if (uuid != null) Tpa.purge(uuid);
    }
}
