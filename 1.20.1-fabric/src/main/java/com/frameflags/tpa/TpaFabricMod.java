package com.frameflags.tpa;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;

public final class TpaFabricMod implements ModInitializer {
    @Override
    public void onInitialize() {
        Tpa.init(FabricLoader.getInstance().getGameDir());
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                TpaCommands.register(dispatcher));
        ServerLifecycleEvents.SERVER_STARTING.register(server -> Tpa.init(FabricLoader.getInstance().getGameDir()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> Tpa.clear());
        ServerTickEvents.END_SERVER_TICK.register(server -> Tpa.onServerTick(server, server.getTickCount()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                Tpa.purge(handler.getPlayer().getUUID()));
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            if (entity instanceof ServerPlayer) {
                Tpa.purge(entity.getUUID());
            }
        });
    }
}
