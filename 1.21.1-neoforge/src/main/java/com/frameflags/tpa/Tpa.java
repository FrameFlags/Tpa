package com.frameflags.tpa;

import com.frameflags.tpa.core.RequestRegistry;
import com.frameflags.tpa.core.TpaConfig;
import com.frameflags.tpa.core.TpaPose;
import com.frameflags.tpa.core.TpaRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /** Probe-only identities; deliberately outside all PlayerList collections. */
    private static final Map<String, ServerPlayer> TEST_PLAYERS = new ConcurrentHashMap<>();

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
            ServerPlayer requester = resolvePlayer(server, request.requester);
            ServerPlayer target = resolvePlayer(server, request.target);
            String requesterName = requester == null ? "a player" : profileName(requester);
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

    /** Adapter-side player lookup. Probe-only identities are consulted first by name. */
    public static ServerPlayer resolveTarget(MinecraftServer server, String name) {
        if (name == null || name.isEmpty()) return null;
        ServerPlayer testPlayer = testPlayerByName(name);
        if (testPlayer != null) return testPlayer;
        if (server == null) return null;
        ServerPlayer exact = server.getPlayerList().getPlayerByName(name);
        if (exact != null) return exact;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.getGameProfile().getName().equalsIgnoreCase(name)) return player;
        }
        return null;
    }

    /**
     * Probe-only registration. Fabricated players have no network connection and must
     * never be inserted into any PlayerList collection: the vanilla tick would touch
     * their null connection. They live only in this side map.
     */
    public static synchronized boolean registerTestPlayer(ServerPlayer player) {
        if (player == null) return false;
        String name = profileName(player);
        if (name == null || name.isEmpty()) return false;
        for (Map.Entry<String, ServerPlayer> entry : TEST_PLAYERS.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name) && entry.getValue() != player) return false;
        }
        TEST_PLAYERS.put(name, player);
        return true;
    }

    /** Probe-only cleanup of the side map; no PlayerList collection is touched. */
    public static synchronized void unregisterTestPlayers() {
        TEST_PLAYERS.clear();
    }

    /** Authlib profile name: getName() in this MC version, name() in newer ones. */
    private static String profileName(ServerPlayer player) {
        if (player == null) return null;
        Object profile = player.getGameProfile();
        if (profile == null) return null;
        for (String accessor : new String[] { "getName", "name" }) {
            try {
                Object value = profile.getClass().getMethod(accessor).invoke(profile);
                if (value instanceof String && !((String) value).isEmpty()) return (String) value;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Try the other accessor name.
            }
        }
        return null;
    }

    private static ServerPlayer testPlayerByName(String name) {
        if (name == null || name.isEmpty()) return null;
        ServerPlayer exact = TEST_PLAYERS.get(name);
        if (exact != null) return exact;
        for (Map.Entry<String, ServerPlayer> entry : TEST_PLAYERS.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) return entry.getValue();
        }
        return null;
    }

    /** Resolves a probe-only player first, then a real online player, by UUID. */
    public static ServerPlayer resolvePlayer(MinecraftServer server, UUID uuid) {
        if (uuid == null) return null;
        for (ServerPlayer player : TEST_PLAYERS.values()) {
            if (uuid.equals(player.getUUID())) return player;
        }
        return server == null ? null : server.getPlayerList().getPlayer(uuid);
    }

    /** Unique online count: real players plus probe-only players (empty in normal play). */
    public static int onlinePlayerCount(MinecraftServer server) {
        Set<UUID> ids = new HashSet<>();
        if (server != null) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (player != null) ids.add(player.getUUID());
            }
        }
        for (ServerPlayer player : TEST_PLAYERS.values()) {
            if (player != null) ids.add(player.getUUID());
        }
        return ids.size();
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
