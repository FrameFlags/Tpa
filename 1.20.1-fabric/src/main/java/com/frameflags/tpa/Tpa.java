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
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Static product API shared by the command adapter, tick hook and in-game probe. */
public final class Tpa {
    private static final RequestRegistry REGISTRY = new RequestRegistry();
    private static final Map<UUID, List<String>> CHAT = new ConcurrentHashMap<>();
    /** Probe-only virtual players: not in the PlayerList, but visible to command lookup. */
    private static final List<ServerPlayer> TEST_PLAYERS = new ArrayList<>();
    private static TpaConfig config = new TpaConfig();
    private static Path gameDir = Path.of(".");

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

    /** Loads the config once; creates the config directory. Requests are never persisted. */
    public static synchronized void init(Path dir) {
        gameDir = dir == null ? Path.of(".") : dir.toAbsolutePath();
        try {
            Files.createDirectories(gameDir.resolve("config"));
            config = TpaConfig.load(configFile());
        } catch (Throwable ignored) {
            config = new TpaConfig();
        }
    }

    /** Single chat sink: every product message is recorded for the no-spam probe. */
    public static void chat(UUID player, String text, int tick) {
        if (player == null) return;
        List<String> log = CHAT.computeIfAbsent(player, key -> new ArrayList<String>());
        synchronized (log) {
            log.add(tick + "|" + text);
            while (log.size() > 256) log.remove(0);
        }
    }

    public static List<String> chatLog(UUID player) {
        List<String> log = player == null ? null : CHAT.get(player);
        if (log == null) return new ArrayList<String>();
        synchronized (log) {
            return new ArrayList<String>(log);
        }
    }

    public static void clearChatLog(UUID player) {
        if (player != null) CHAT.remove(player);
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
        ServerPlayer found = server.getPlayerList().getPlayerByName(needle);
        if (found == null) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (needle.equalsIgnoreCase(player.getGameProfile().getName())) {
                    found = player;
                    break;
                }
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

    /** Drains expired requests exactly once, sending one message to each side; never teleports. */
    public static void onServerTick(MinecraftServer server, int tick) {
        if (server == null) return;
        List<TpaRequest> due = REGISTRY.tick(tick);
        for (TpaRequest request : due) {
            ServerPlayer requester = findPlayer(server, request.requester);
            ServerPlayer target = findPlayer(server, request.target);
            String requesterName = requester == null ? "A player" : requester.getGameProfile().getName();
            if (requester != null) {
                message(requester, ChatFormatting.RED + "Your teleport request to " + request.targetName + " has expired.", tick);
            }
            if (target != null) {
                message(target, ChatFormatting.RED + "Teleport request from " + requesterName + " has expired.", tick);
            }
        }
    }

    /** Removes every request involving this player (death, disconnect, server stop). */
    public static void purge(UUID player) {
        if (player != null) REGISTRY.purge(player);
    }

    public static void clear() {
        REGISTRY.clear();
        CHAT.clear();
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
        ServerLevel level = null;
        ResourceLocation id = ResourceLocation.tryParse(pose.dimension);
        if (id != null && server != null) {
            ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, id);
            level = server.getLevel(key);
        }
        if (level == null) level = player.serverLevel();
        player.teleportTo(level, pose.x, pose.y, pose.z, java.util.Set.<net.minecraft.world.entity.RelativeMovement>of(), pose.yaw, pose.pitch);
        player.setDeltaMovement(Vec3.ZERO);
    }
}
