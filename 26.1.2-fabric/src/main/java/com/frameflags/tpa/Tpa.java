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

    /** Server stop: all state is ephemeral. */
    public static void clear() {
        REGISTRY.clear();
        CHAT.clear();
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
        ServerPlayer target = server.getPlayerList().getPlayer(player);
        if (target != null) target.sendSystemMessage(Component.literal(text));
    }

    /** Online player lookup by exact name, then case-insensitively. */
    public static ServerPlayer resolveTarget(MinecraftServer server, String name) {
        if (server == null || name == null) return null;
        ServerPlayer exact = server.getPlayerList().getPlayer(name);
        if (exact != null) return exact;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (name.equalsIgnoreCase(player.getGameProfile().name())) return player;
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
