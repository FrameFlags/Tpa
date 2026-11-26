package com.frameflags.tpa;

import com.frameflags.tpa.core.RequestRegistry;
import com.frameflags.tpa.core.TpaConfig;
import com.frameflags.tpa.core.TpaPose;
import com.frameflags.tpa.core.TpaRequest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Registry;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Static product API shared by the command adapter, the tick hook and the
 * in-game probe. The probe reads this class reflectively so it still loads when
 * the product jar is absent (the CI negative control).
 */
public final class Tpa {
    /** Every chat message emitted by the adapter, in send order. Probe-only observation. */
    public static final List<String> recentMessages = Collections.synchronizedList(new ArrayList<String>());

    private static final Map<UUID, List<String>> CHAT = new ConcurrentHashMap<UUID, List<String>>();
    private static final RequestRegistry REGISTRY = new RequestRegistry();
    private static final AtomicLong TICK = new AtomicLong();
    /** Probe-only virtual players: not in the PlayerList, but visible to command lookup. */
    private static final List<ServerPlayer> TEST_PLAYERS = new ArrayList<ServerPlayer>();

    private static TpaConfig config = new TpaConfig();
    private static Path gameDir = Paths.get(".");

    private Tpa() {
    }

    public static TpaConfig config() {
        return config;
    }

    public static RequestRegistry registry() {
        return REGISTRY;
    }

    public static Path configFile() {
        return gameDir.resolve("config").resolve("tpa.json");
    }

    /** Loads config once from &lt;gameDir&gt;/config/tpa.json; creates the directory. */
    public static synchronized void init(Path dir) {
        gameDir = dir == null ? Paths.get(".") : dir.toAbsolutePath().normalize();
        try {
            Files.createDirectories(gameDir.resolve("config"));
        } catch (Throwable ignored) {
            // In-memory defaults still apply.
        }
        config = TpaConfig.load(configFile());
    }

    /** The single authoritative tick counter. Deadlines use this clock. */
    public static long tick() {
        return TICK.get();
    }

    /** Advances exactly one tick and drains every expired request, once. */
    public static List<TpaRequest> advanceTick() {
        return REGISTRY.tick(TICK.incrementAndGet());
    }

    /** Single message sink: one line per player, prefixed with the tick for the no-spam probe. */
    public static void chat(UUID player, String text, int tick) {
        if (text == null) return;
        String line = tick + "|" + text;
        recentMessages.add(line);
        while (recentMessages.size() > 256) recentMessages.remove(0);
        if (player != null) {
            List<String> log = CHAT.get(player);
            if (log == null) {
                List<String> created = new ArrayList<String>();
                log = CHAT.putIfAbsent(player, created);
                if (log == null) log = created;
            }
            synchronized (log) {
                log.add(line);
                while (log.size() > 256) log.remove(0);
            }
        }
    }

    /** Probe-only view of what a player actually received. */
    public static List<String> chatLog(UUID player) {
        List<String> log = player == null ? null : CHAT.get(player);
        if (log == null) return Collections.emptyList();
        synchronized (log) {
            return new ArrayList<String>(log);
        }
    }

    /** Probe-only reset between assertions. */
    public static void clearChatLog(UUID player) {
        if (player == null) return;
        CHAT.remove(player);
        recentMessages.clear();
    }

    /** Registers a fabricated probe player so resolveTarget can find it. Probe-only hook. */
    public static synchronized void registerTestPlayer(ServerPlayer player) {
        if (player != null && !TEST_PLAYERS.contains(player)) TEST_PLAYERS.add(player);
    }

    public static synchronized void unregisterTestPlayers() {
        TEST_PLAYERS.clear();
    }

    /** True when somebody other than self is available as a teleport target. */
    public static boolean hasOtherPlayers(ServerPlayer self, MinecraftServer server) {
        if (server == null) return false;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (self == null || !player.getUUID().equals(self.getUUID())) return true;
        }
        synchronized (TEST_PLAYERS) {
            for (ServerPlayer player : TEST_PLAYERS) {
                if (self == null || !player.getUUID().equals(self.getUUID())) return true;
            }
        }
        return false;
    }

    /** Adapter-side player lookup: online players first, then probe-only virtual players. */
    public static ServerPlayer resolveTarget(MinecraftServer server, String name) {
        if (server == null || name == null) return null;
        String needle = name.trim();
        if (needle.isEmpty()) return null;
        ServerPlayer found = null;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player != null && needle.equalsIgnoreCase(player.getGameProfile().getName())) {
                found = player;
                break;
            }
        }
        if (found == null) {
            synchronized (TEST_PLAYERS) {
                for (ServerPlayer player : TEST_PLAYERS) {
                    if (needle.equalsIgnoreCase(player.getGameProfile().getName())) {
                        found = player;
                        break;
                    }
                }
            }
        }
        return found;
    }

    /** Finds a player by UUID in either the PlayerList or the probe-only virtual list. */
    public static ServerPlayer findPlayer(MinecraftServer server, UUID id) {
        if (server == null || id == null) return null;
        ServerPlayer player = server.getPlayerList().getPlayer(id);
        if (player != null) return player;
        synchronized (TEST_PLAYERS) {
            for (ServerPlayer candidate : TEST_PLAYERS) {
                if (id.equals(candidate.getUUID())) return candidate;
            }
        }
        return null;
    }

    /** Removes every request involving a player (disconnect/death). */
    public static void purge(UUID player) {
        if (player != null) REGISTRY.purge(player);
        CHAT.remove(player);
    }

    /** Server stop: no request and no probe chat state survives. */
    public static void clear() {
        REGISTRY.clear();
        CHAT.clear();
        recentMessages.clear();
        unregisterTestPlayers();
    }

    /** Sends exactly one chat line and records it in the single sink. */
    public static void message(ServerPlayer player, String text, int tick) {
        if (player == null) return;
        chat(player.getUUID(), text, tick);
        player.sendSystemMessage(Component.literal(text));
    }

    /** Cross-dimension teleport to an exact pose; missing dimension falls back to current level. */
    public static void teleport(ServerPlayer player, TpaPose pose, MinecraftServer server) {
        if (player == null || pose == null) return;
        ServerLevel level = player.getLevel();
        ResourceLocation id = ResourceLocation.tryParse(pose.dimension);
        if (id != null && server != null) {
            try {
                ResourceKey<Level> key = ResourceKey.create(Registry.DIMENSION_REGISTRY, id);
                ServerLevel resolved = server.getLevel(key);
                if (resolved != null) level = resolved;
            } catch (RuntimeException ignored) {
                // Missing dimension falls back to the player's current level.
            }
        }
        player.teleportTo(level, pose.x, pose.y, pose.z, pose.yaw, pose.pitch);
        player.setDeltaMovement(Vec3.ZERO);
    }
}
