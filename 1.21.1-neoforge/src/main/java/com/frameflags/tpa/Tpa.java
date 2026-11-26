package com.frameflags.tpa;

import com.frameflags.tpa.core.RequestRegistry;
import com.frameflags.tpa.core.TpaConfig;
import com.frameflags.tpa.core.TpaPose;
import com.frameflags.tpa.core.TpaRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Static product API shared by the command adapter, the tick hook and the lifecycle hooks.
 * All request state is in memory; the only file written is config/tpa.json.
 */
public final class Tpa {
    private static final TpaConfig CONFIG = new TpaConfig();
    private static final RequestRegistry REGISTRY = new RequestRegistry();
    private static final Map<UUID, List<String>> CHAT = new ConcurrentHashMap<>();
    private static Path gameDir = Path.of(".");

    private Tpa() {
    }

    public static TpaConfig config() {
        return CONFIG;
    }

    public static RequestRegistry registry() {
        return REGISTRY;
    }

    public static Path configFile() {
        return gameDir.resolve("config").resolve("tpa.json");
    }

    /** Creates the config directory and loads config once (and again on server start). */
    public static synchronized void init(Path dir) {
        gameDir = dir == null ? Path.of(".") : dir.toAbsolutePath();
        try {
            Files.createDirectories(gameDir.resolve("config"));
            TpaConfig loaded = TpaConfig.load(configFile());
            if (loaded != null) {
                CONFIG.expirySeconds = loaded.expirySeconds;
                CONFIG.maxOutgoing = loaded.maxOutgoing;
            }
        } catch (Throwable ignored) {
            // In-memory defaults stay in effect for this session.
        }
    }

    /** The single chat sink: every line this product sends is recorded for the no-spam probe. */
    public static void chat(UUID player, String text, int tick) {
        if (player == null) return;
        List<String> log = CHAT.computeIfAbsent(player, key -> new ArrayList<>());
        synchronized (log) {
            log.add(tick + "|" + text);
            while (log.size() > 256) log.remove(0);
        }
    }

    public static List<String> chatLog(UUID player) {
        List<String> log = CHAT.get(player);
        if (log == null) return new ArrayList<>();
        synchronized (log) {
            return new ArrayList<>(log);
        }
    }

    public static void clearChatLog(UUID player) {
        CHAT.remove(player);
    }

    /** Sends exactly one message to one player and records it in the shared chat sink. */
    public static void notify(ServerPlayer player, String text, int tick) {
        if (player == null) return;
        chat(player.getUUID(), text, tick);
        player.sendSystemMessage(Component.literal(text));
    }

    /** Drains expired requests exactly once and notifies both sides. Never teleports. */
    public static void onServerTick(MinecraftServer server, int tick) {
        if (server == null) return;
        for (TpaRequest request : REGISTRY.tick(tick)) {
            ServerPlayer requester = server.getPlayerList().getPlayer(request.requester);
            ServerPlayer target = server.getPlayerList().getPlayer(request.target);
            String requesterName = requester == null ? "a player" : requester.getGameProfile().getName();
            notify(requester, ChatFormatting.RED + "Your teleport request to " + request.targetName + " has expired.", tick);
            notify(target, ChatFormatting.RED + "Teleport request from " + requesterName + " has expired.", tick);
        }
    }

    /** Removes every request involving a player (used on disconnect and death). */
    public static void purge(UUID player) {
        if (player != null) REGISTRY.purge(player);
    }

    /** Server stopped: drop all in-memory requests and observation data. */
    public static void clear() {
        REGISTRY.clear();
        CHAT.clear();
    }

    /** Adapter-side player lookup by online name, case-insensitive fallback. */
    public static ServerPlayer resolveTarget(MinecraftServer server, String name) {
        if (server == null || name == null || name.isEmpty()) return null;
        ServerPlayer exact = server.getPlayerList().getPlayerByName(name);
        if (exact != null) return exact;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.getGameProfile().getName().equalsIgnoreCase(name)) return player;
        }
        return null;
    }

    /** Captures the exact current position and facing of a player. */
    public static TpaPose poseOf(ServerPlayer player) {
        if (player == null) return null;
        return new TpaPose(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot(),
                player.level().dimension().location().toString());
    }

    /** Teleports with exact yaw/pitch, cross-dimension support and zero momentum. */
    public static void teleport(ServerPlayer player, TpaPose pose, MinecraftServer server) {
        if (player == null || pose == null || server == null) return;
        ServerLevel level = null;
        ResourceLocation id = ResourceLocation.tryParse(pose.dimension);
        if (id != null) {
            try {
                level = server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
            } catch (Throwable ignored) {
                // Unknown dimension: fall back to the player's current level.
            }
        }
        if (level == null) level = player.serverLevel();
        player.teleportTo(level, pose.x, pose.y, pose.z, java.util.Set.of(), pose.yaw, pose.pitch);
        player.setDeltaMovement(Vec3.ZERO);
    }
}
