package com.frameflags.tpa;

import com.frameflags.tpa.core.RequestRegistry;
import com.frameflags.tpa.core.TpaConfig;
import com.frameflags.tpa.core.TpaPose;
import com.frameflags.tpa.core.TpaRequest;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Static product API: request state, config, chat sink and teleport adapter. */
public final class Tpa {
    private static final RequestRegistry REGISTRY = new RequestRegistry();
    private static final Map<UUID, List<String>> CHAT = new ConcurrentHashMap<>();
    /** Probe-only virtual players. Deliberately absent from PlayerList: no connection to broadcast to. */
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

    /** Creates the config directory and loads config once for this server session. */
    public static synchronized void init(Path dir) {
        gameDir = dir == null ? Path.of(".") : dir.toAbsolutePath();
        try {
            Files.createDirectories(gameDir.resolve("config"));
        } catch (Throwable ignored) {
            // In-memory defaults still apply.
        }
        config = TpaConfig.load(configFile());
    }

    /** Single chat sink: every product message is recorded here for the no-spam probe. */
    public static void chat(UUID player, String text, int tick) {
        if (player == null) return;
        List<String> log = CHAT.computeIfAbsent(player, key -> new ArrayList<>());
        synchronized (log) {
            log.add(tick + "|" + text);
            while (log.size() > 256) log.remove(0);
        }
    }

    public static List<String> chatLog(UUID player) {
        List<String> log = player == null ? null : CHAT.get(player);
        if (log == null) return new ArrayList<>();
        synchronized (log) {
            return new ArrayList<>(log);
        }
    }

    public static void clearChatLog(UUID player) {
        if (player != null) CHAT.remove(player);
    }

    /** Probe-only hook: registers a fabricated player for lookup without touching PlayerList. */
    public static synchronized void registerTestPlayer(ServerPlayer player) {
        if (player != null && !TEST_PLAYERS.contains(player)) TEST_PLAYERS.add(player);
    }

    /** Probe-only hook: removes every fabricated player. */
    public static synchronized void unregisterTestPlayers() {
        TEST_PLAYERS.clear();
    }

    /** Profile name via reflection: getName() on older versions, name() on 26.x records. */
    private static String profileName(ServerPlayer player) {
        if (player == null) return null;
        Object profile;
        try {
            profile = player.getGameProfile();
        } catch (Throwable ignored) {
            return null;
        }
        if (profile == null) return null;
        for (String accessor : new String[] { "name", "getName" }) {
            try {
                Method method = profile.getClass().getMethod(accessor);
                Object value = method.invoke(profile);
                if (value instanceof String) return (String) value;
            } catch (Throwable ignored) {
                // Try the other accessor.
            }
        }
        return null;
    }

    /** True when a usable target other than self exists, real or probe-only. */
    public static synchronized boolean hasOtherPlayers(ServerPlayer self, MinecraftServer server) {
        if (server == null) return false;
        UUID selfId = self == null ? null : self.getUUID();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player != null && !player.getUUID().equals(selfId)) return true;
        }
        for (ServerPlayer player : TEST_PLAYERS) {
            if (player != null && !player.getUUID().equals(selfId)) return true;
        }
        return false;
    }

    /** Finds a player by UUID in the real roster first, then the probe-only list. */
    public static synchronized ServerPlayer findPlayer(MinecraftServer server, UUID id) {
        if (server == null || id == null) return null;
        ServerPlayer real = server.getPlayerList().getPlayer(id);
        if (real != null) return real;
        for (ServerPlayer candidate : TEST_PLAYERS) {
            if (candidate != null && id.equals(candidate.getUUID())) return candidate;
        }
        return null;
    }

    /** Server stop: all state is ephemeral. */
    public static void clear() {
        REGISTRY.clear();
        CHAT.clear();
        unregisterTestPlayers();
    }

    /** Disconnect, death and logout safety net. */
    public static void purge(UUID player) {
        if (player != null) REGISTRY.purge(player);
    }

    /** Sends one message to one other participant (used by command adapters). */
    public static void tell(MinecraftServer server, UUID player, String text, int tick) {
        if (player == null) return;
        chat(player, text, tick);
        if (server == null) return;
        ServerPlayer target = findPlayer(server, player);
        if (target != null) target.sendSystemMessage(Component.literal(text));
    }

    /** Online player lookup: probe-only players by name first, then normal server lookup. */
    public static ServerPlayer resolveTarget(MinecraftServer server, String name) {
        if (name == null) return null;
        String needle = name.trim();
        if (needle.isEmpty()) return null;
        synchronized (Tpa.class) {
            for (ServerPlayer player : TEST_PLAYERS) {
                String candidate = profileName(player);
                if (needle.equals(candidate)) return player;
            }
            for (ServerPlayer player : TEST_PLAYERS) {
                String candidate = profileName(player);
                if (needle.equalsIgnoreCase(candidate)) return player;
            }
        }
        if (server == null) return null;
        ServerPlayer exact = server.getPlayerList().getPlayer(needle);
        if (exact != null) return exact;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            String candidate = profileName(player);
            if (candidate != null && needle.equalsIgnoreCase(candidate)) return player;
        }
        return null;
    }

    /** Drains expired requests exactly once, notifying both sides without teleporting. */
    public static void onServerTick(MinecraftServer server, int tick) {
        if (server == null) return;
        for (ServerPlayer player : new ArrayList<>(server.getPlayerList().getPlayers())) {
            if (player.isDeadOrDying() || player.hasDisconnected()) purge(player.getUUID());
        }
        for (TpaRequest expired : REGISTRY.tick(tick)) {
            String suffix = expired.targetName == null ? "a player" : expired.targetName;
            tell(server, expired.requester, ChatFormatting.RED + "Teleport request to " + suffix + " expired.", tick);
            tell(server, expired.target, ChatFormatting.RED + "Teleport request from " + suffix + " expired.", tick);
        }
    }

    /** Version-specific teleport: exact pose, stable dimension key, momentum zeroed. */
    public static void teleport(ServerPlayer player, TpaPose pose, MinecraftServer server) {
        if (player == null || pose == null || server == null) return;
        ServerLevel level = null;
        Identifier id = Identifier.tryParse(pose.dimension);
        if (id != null) {
            ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, id);
            level = server.getLevel(key);
        }
        if (level == null) level = (ServerLevel) player.level();
        player.teleportTo(level, pose.x, pose.y, pose.z, java.util.Set.of(), pose.yaw, pose.pitch, false);
        player.setDeltaMovement(Vec3.ZERO);
    }

    /** Records and sends exactly one message from a command source. */
    static void message(CommandSourceStack source, ServerPlayer player, String text, boolean success) {
        if (source == null) return;
        if (player != null) chat(player.getUUID(), text, source.getServer().getTickCount());
        Component component = Component.literal(text);
        if (success) source.sendSuccess(() -> component, false);
        else source.sendFailure(component);
    }
}
