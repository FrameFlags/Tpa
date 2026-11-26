package com.frameflags.tpa;

import com.frameflags.tpa.core.RequestRegistry;
import com.frameflags.tpa.core.TpaConfig;
import com.frameflags.tpa.core.TpaRequest;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;

/**
 * Static product API shared by the command adapter, the tick handler and the
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
    private static final List<EntityPlayerMP> TEST_PLAYERS = new ArrayList<EntityPlayerMP>();

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
        if (dir != null) gameDir = dir.toAbsolutePath().normalize();
        try {
            java.nio.file.Files.createDirectories(gameDir.resolve("config"));
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

    /** Single message sink: records one line for the player and one in the global log. */
    public static void chat(UUID player, String text, int tick) {
        if (text == null) return;
        recentMessages.add(text);
        if (player != null) {
            List<String> log = CHAT.get(player);
            if (log == null) {
                List<String> created = new ArrayList<String>();
                log = CHAT.putIfAbsent(player, created);
                if (log == null) log = created;
            }
            synchronized (log) {
                log.add(text);
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
        List<String> log = CHAT.get(player);
        if (log != null) synchronized (log) { log.clear(); }
        recentMessages.clear();
    }

    /** Registers a fabricated probe player so resolveTarget can find it. Probe-only hook. */
    public static synchronized void registerTestPlayer(EntityPlayerMP player) {
        if (player != null && !TEST_PLAYERS.contains(player)) TEST_PLAYERS.add(player);
    }

    public static synchronized void unregisterTestPlayers() {
        TEST_PLAYERS.clear();
    }

    /** Adapter-side player lookup: online players first, then probe-only virtual players. */
    public static EntityPlayerMP resolveTarget(MinecraftServer server, String name) {
        if (server == null || name == null) return null;
        String needle = name.trim();
        if (needle.isEmpty()) return null;
        for (EntityPlayerMP player : server.getPlayerList().getPlayers()) {
            if (player != null && needle.equalsIgnoreCase(player.getName())) return player;
        }
        synchronized (TEST_PLAYERS) {
            for (EntityPlayerMP player : TEST_PLAYERS) {
                if (needle.equalsIgnoreCase(player.getName())) return player;
            }
        }
        return null;
    }

    /** Finds a player by UUID in either the PlayerList or the probe-only virtual list. */
    public static EntityPlayerMP findPlayer(MinecraftServer server, UUID id) {
        if (server == null || id == null) return null;
        EntityPlayerMP player = server.getPlayerList().getPlayerByUUID(id);
        if (player != null) return player;
        synchronized (TEST_PLAYERS) {
            for (EntityPlayerMP candidate : TEST_PLAYERS) {
                if (id.equals(candidate.getUniqueID())) return candidate;
            }
        }
        return null;
    }

    /** Removes every request involving a player (disconnect/death). */
    public static void purge(UUID player) {
        REGISTRY.purge(player);
        CHAT.remove(player);
    }

    /** Server stop: no request and no probe chat state survives. */
    public static void clear() {
        REGISTRY.clear();
        CHAT.clear();
        recentMessages.clear();
        unregisterTestPlayers();
    }
}
