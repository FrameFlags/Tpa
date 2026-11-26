package com.frameflags.tpa_test;

import com.mojang.authlib.GameProfile;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.phys.Vec3;

/**
 * In-game TPA probe. Every state change goes through the real Brigadier dispatcher as a
 * real ServerPlayer or a fabricated ServerPlayer registered through the product's
 * probe-only hook (never into PlayerList). Product classes are touched only by
 * reflection, so this jar also loads for the negative control (product jar absent).
 * A skipped check is never recorded as a pass.
 */
public final class TpaProbe {
    private static final double TOL = 0.01D;
    private static final int BOT_COUNT = 6;
    private static final int SETTLE_TICKS = 40;
    private static final int TOTAL_TIMEOUT = 1800;

    private static String cell = "unknown";
    private static Class<?> productClass;
    private static boolean productPresent;
    private static boolean finished;
    private static int expirySeconds = 60;

    private static MinecraftServer server;
    private static ServerPlayer joined;
    private static UUID joinedId;
    private static ServerPlayer[] bots;
    private static int bootTick = -1;
    private static int startTick;
    private static int phase;
    private static int phaseTick;
    private static int expiryStart;
    private static boolean expiryRequested;
    private static boolean expiryEarlyTeleport;
    private static Pose expiryPose;

    private static final LinkedHashSet<String> passed = new LinkedHashSet<>();
    private static final LinkedHashSet<String> failed = new LinkedHashSet<>();
    private static final Map<UUID, List<String>> observedChat = new HashMap<>();

    private TpaProbe() {
    }

    public static void bootstrap(String cellId) {
        if (finished) return;
        cell = cellId;
        cleanResults();
        try {
            productClass = Class.forName("com.frameflags.tpa.Tpa");
            productPresent = true;
        } catch (Throwable ignored) {
            productPresent = false;
        }
        readConfig();
        if (!productPresent) {
            check(false, "no_product");
            finish();
        }
    }

    /** Adopts the first joined player; pins the pose so exact assertions are stable. */
    public static void onJoin(ServerPlayer player, int tick) {
        if (finished || !productPresent || joined != null || player == null) return;
        joined = player;
        joinedId = player.getUUID();
        startTick = tick;
        pin(player);
        clearChat(joinedId);
    }

    public static void onTick(MinecraftServer incoming, int tick) {
        if (finished || !productPresent || incoming == null) return;
        server = incoming;
        if (bootTick < 0) bootTick = tick;

        if (joined == null) {
            // Headless launchers may not join a client. Fabricate the primary player too.
            if (tick - bootTick < 400) return;
            joined = fabricate(server, "TpaPlayer");
            if (joined == null) {
                check(false, "no_player");
                finish();
                return;
            }
            joinedId = joined.getUUID();
            startTick = tick;
            pin(joined);
        } else {
            ServerPlayer live = server.getPlayerList().getPlayer(joinedId);
            if (live != null) joined = live;
        }

        if (bots == null) {
            bots = new ServerPlayer[BOT_COUNT];
            for (int i = 0; i < bots.length; i++) {
                bots[i] = fabricate(server, "TpaBot" + i);
                if (bots[i] != null) pin(bots[i]);
            }
            for (ServerPlayer bot : bots) {
                if (bot == null) {
                    check(false, "fabrication_failed");
                    finish();
                    return;
                }
                clearChat(bot.getUUID());
            }
            phaseTick = tick;
        }

        if (tick - startTick < SETTLE_TICKS) return;
        if (tick - startTick > TOTAL_TIMEOUT) {
            check(false, "timeout");
            finish();
            return;
        }
        if (tick - phaseTick < 3) return;

        switch (phase) {
            case 0:
                phaseValidation();
                nextPhase(tick);
                break;
            case 1:
                phaseAccept();
                nextPhase(tick);
                break;
            case 2:
                phaseHere();
                nextPhase(tick);
                break;
            case 3:
                phaseDeny();
                nextPhase(tick);
                break;
            case 4:
                phaseCancel();
                nextPhase(tick);
                break;
            case 5:
                phaseLimit();
                nextPhase(tick);
                break;
            case 6:
                phaseExpiryStart();
                nextPhase(tick);
                break;
            case 7:
                if (phaseExpiryWait(tick)) nextPhase(tick);
                break;
            default:
                phaseFinish();
                break;
        }
    }

    private static void nextPhase(int tick) {
        phase++;
        phaseTick = tick;
    }

    // ---------------- phases ----------------

    private static void phaseValidation() {
        clearChat(joinedId);

        int rc = dispatch(joined, "tpa " + profileName(bots[0]));
        List<?> inbound = inbound(bots[0].getUUID());
        check(rc > 0 && inbound != null && inbound.size() == 1 && chatCount(joinedId) == 1, "request_ok");

        clearChat(joinedId);
        int size = registrySize();
        rc = dispatch(joined, "tpa " + profileName(joined));
        check(rc == 0 && registrySize() == size && chatCount(joinedId) == 1, "self_rejected");

        clearChat(joinedId);
        rc = dispatch(joined, "tpa GhostPlayerZZZ");
        check(rc == 0 && registrySize() == size && chatCount(joinedId) == 1, "no_such_player");

        clearChat(joinedId);
        rc = dispatch(joined, "tpa " + profileName(bots[0]));
        check(rc == 0 && registrySize() == size && chatCount(joinedId) == 1, "duplicate_rejected");
    }

    private static void phaseAccept() {
        // The request from phase 0 is still pending: requester=joined, target=bots[0].
        setPose(bots[0], 200.5D, 70.5D, 200.5D, 33.0F, 12.0F);
        setPose(joined, 500.5D, 100.5D, 500.5D, 0.0F, 0.0F);
        clearChat(joinedId);
        int rc = dispatch(bots[0], "tpaccept");
        boolean arrived = matches(pose(joined), 200.5D, 70.5D, 200.5D, 33.0F, 12.0F);
        List<?> inbound = inbound(bots[0].getUUID());
        boolean removed = inbound == null || inbound.isEmpty();
        check(rc > 0 && arrived && removed && chatCount(joinedId) == 1, "accept_ok");
    }

    private static void phaseHere() {
        setPose(joined, 300.5D, 80.5D, 300.5D, 120.0F, -30.0F);
        int rc = dispatch(joined, "tpahere " + profileName(bots[0]));
        setPose(joined, 600.5D, 130.5D, 600.5D, 0.0F, 0.0F);
        clearChat(bots[0].getUUID());
        int rc2 = dispatch(bots[0], "tpaccept");
        boolean atSnapshot = matches(pose(bots[0]), 300.5D, 80.5D, 300.5D, 120.0F, -30.0F);
        boolean notAtLive = !matches(pose(bots[0]), 600.5D, 130.5D, 600.5D, 0.0F, 0.0F);
        check(rc > 0 && rc2 > 0 && atSnapshot && notAtLive && chatCount(bots[0].getUUID()) == 1, "tpahere_ok");
        check(atSnapshot && notAtLive, "snapshot_ok");
    }

    private static void phaseDeny() {
        setPose(joined, 700.5D, 90.5D, 700.5D, 45.0F, 5.0F);
        Pose before = pose(joined);
        int rc = dispatch(joined, "tpa " + profileName(bots[0]));
        clearChat(joinedId);
        int rc2 = dispatch(bots[0], "tpdeny");
        List<?> inbound = inbound(bots[0].getUUID());
        boolean removed = inbound == null || inbound.isEmpty();
        check(rc > 0 && rc2 > 0 && removed && matches(pose(joined), before) && chatCount(joinedId) == 1, "deny_ok");
    }

    private static void phaseCancel() {
        Pose before = pose(joined);
        int rc = dispatch(joined, "tpa " + profileName(bots[0]));
        clearChat(joinedId);
        int rc2 = dispatch(joined, "tpacancel");
        List<?> outgoing = outgoing(joinedId);
        boolean removed = outgoing == null || outgoing.isEmpty();
        check(rc > 0 && rc2 > 0 && removed && matches(pose(joined), before) && chatCount(joinedId) == 1, "cancel_ok");
    }

    private static void phaseLimit() {
        for (ServerPlayer bot : bots) {
            dispatch(joined, "tpa " + profileName(bot));
        }
        List<?> outgoing = outgoing(joinedId);
        boolean evicted = false;
        boolean newestPresent = false;
        int size = outgoing == null ? -1 : outgoing.size();
        if (outgoing != null) {
            for (Object request : outgoing) {
                Object target = field(request, "target");
                if (!(target instanceof UUID)) continue;
                if (bots[0].getUUID().equals(target)) evicted = true;
                if (bots[5].getUUID().equals(target)) newestPresent = true;
            }
        }
        check(size == 5 && !evicted && newestPresent, "outgoing_limit");
        clearChat(joinedId);
        int rc = dispatch(joined, "tpacancelall");
        List<?> after = outgoing(joinedId);
        check(rc > 0 && after != null && after.isEmpty() && chatCount(joinedId) == 1, "cancelall_ok");
    }

    private static void phaseExpiryStart() {
        expiryPose = pose(joined);
        int rc = dispatch(joined, "tpa " + profileName(bots[0]));
        expiryRequested = rc > 0;
        expiryStart = server.getTickCount();
        expiryEarlyTeleport = false;
        clearChat(joinedId);
    }

    /** Returns true when the expiry window has been fully observed. */
    private static boolean phaseExpiryWait(int tick) {
        long deadline = expiryStart + (long) Math.max(0, expirySeconds) * 20L + 5L;
        if (tick < deadline) {
            if (!matches(pose(joined), expiryPose)) expiryEarlyTeleport = true;
            return false;
        }
        List<?> inbound = inbound(bots[0].getUUID());
        boolean removed = inbound == null || inbound.isEmpty();
        check(expiryRequested && removed && !expiryEarlyTeleport
                && matches(pose(joined), expiryPose) && chatCount(joinedId) == 1, "expiry_ok");
        return true;
    }

    private static void phaseFinish() {
        boolean spamFree = true;
        for (Map.Entry<UUID, List<String>> entry : observedChat.entrySet()) {
            if (hasRepeatedLine(entry.getValue())) spamFree = false;
        }
        List<String> current = chatLog(joinedId);
        if (hasRepeatedLine(current)) spamFree = false;
        for (ServerPlayer bot : bots) {
            if (bot != null && hasRepeatedLine(chatLog(bot.getUUID()))) spamFree = false;
        }
        check(!observedChat.isEmpty() && spamFree, "no_spam");
        finish();
    }

    private static boolean hasRepeatedLine(List<String> log) {
        for (int i = 1; i < log.size(); i++) {
            String previous = log.get(i - 1);
            String current = log.get(i);
            int barPrev = previous.indexOf('|');
            int barCur = current.indexOf('|');
            if (barPrev < 0 || barCur < 0) continue;
            if (previous.substring(barPrev + 1).equals(current.substring(barCur + 1))) {
                long delta = parseLong(current.substring(0, barCur)) - parseLong(previous.substring(0, barPrev));
                if (Math.abs(delta) <= 1) return true;
            }
        }
        return false;
    }

    // ---------------- dispatcher: the only mutation path ----------------

    private static int dispatch(ServerPlayer sender, String command) {
        try {
            CommandSourceStack source = sender.createCommandSourceStack().withPermission(4);
            return server.getCommands().getDispatcher().execute(command, source);
        } catch (Throwable t) {
            System.out.println("[TPA_PROBE] dispatch_failed command=" + command + " error=" + t);
            return -1;
        }
    }

    private static boolean registerTestPlayer(ServerPlayer player) {
        if (productClass == null) return false;
        try {
            Object result = productClass.getMethod("registerTestPlayer", ServerPlayer.class).invoke(null, player);
            return Boolean.TRUE.equals(result);
        } catch (ReflectiveOperationException | RuntimeException t) {
            System.out.println("[TPA_PROBE] registerTestPlayer failed: " + t);
            return false;
        }
    }

    private static boolean unregisterTestPlayers() {
        if (productClass == null) return true;
        try {
            productClass.getMethod("unregisterTestPlayers").invoke(null);
            return true;
        } catch (ReflectiveOperationException | RuntimeException t) {
            System.out.println("[TPA_PROBE] unregisterTestPlayers failed: " + t);
            return false;
        }
    }

    /** Authlib profile name via reflection: getName() here, name() in newer libraries. */
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

    // ---------------- fake player ----------------

    private static ServerPlayer fabricate(MinecraftServer minecraftServer, String name) {
        try {
            ServerLevel level = minecraftServer.overworld();
            GameProfile profile = new GameProfile(UUID.randomUUID(), name);
            ProbePlayer fake = new ProbePlayer(minecraftServer, level, profile);
            setPose(fake, 10.5D, 64.5D, 10.5D, 0.0F, 0.0F);
            if (!registerTestPlayer(fake)) {
                System.out.println("[TPA_PROBE] register_test_player_failed name=" + name);
                return null;
            }
            return fake;
        } catch (Throwable t) {
            System.out.println("[TPA_PROBE] fabricate_failed name=" + name + " error=" + t);
            return null;
        }
    }

    private static void pin(ServerPlayer player) {
        try {
            player.setNoGravity(true);
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
            player.setDeltaMovement(Vec3.ZERO);
        } catch (Throwable ignored) {
            // Fabricated players still have usable abilities.
        }
    }

    private static void setPose(ServerPlayer player, double x, double y, double z, float yaw, float pitch) {
        player.setPos(x, y, z);
        player.setYRot(yaw);
        player.setXRot(pitch);
        player.setDeltaMovement(Vec3.ZERO);
    }

    /** Unconnected player used only as a command source and teleport target. */
    private static final class ProbePlayer extends ServerPlayer {
        ProbePlayer(MinecraftServer minecraftServer, ServerLevel level, GameProfile profile) {
            super(minecraftServer, level, profile, ClientInformation.createDefault());
        }

        @Override
        public boolean teleportTo(ServerLevel level, double x, double y, double z,
                                  Set<RelativeMovement> relative, float yaw, float pitch) {
            setPos(x, y, z);
            setYRot(yaw);
            setXRot(pitch);
            setDeltaMovement(Vec3.ZERO);
            return true;
        }

        @Override
        public void sendSystemMessage(Component component) {
        }

        @Override
        public void sendSystemMessage(Component component, boolean actionBar) {
        }
    }

    // ---------------- read-only product observation through reflection ----------------

    private static Object invokeStatic(String name, Class<?>[] types, Object... args) {
        try {
            Method method = productClass.getMethod(name, types);
            return method.invoke(null, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) {
        try {
            Method method = target.getClass().getMethod(name, types);
            return method.invoke(target, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object field(Object target, String name) {
        try {
            return target.getClass().getField(name).get(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object registry() {
        return invokeStatic("registry", new Class<?>[0]);
    }

    private static int registrySize() {
        Object reg = registry();
        Object size = reg == null ? null : invoke(reg, "size", new Class<?>[0]);
        return size instanceof Integer ? (Integer) size : -1;
    }

    private static List<?> inbound(UUID player) {
        Object reg = registry();
        Object result = reg == null ? null : invoke(reg, "inbound", new Class<?>[] { UUID.class }, player);
        return result instanceof List<?> ? (List<?>) result : null;
    }

    private static List<?> outgoing(UUID player) {
        Object reg = registry();
        Object result = reg == null ? null : invoke(reg, "outgoing", new Class<?>[] { UUID.class }, player);
        return result instanceof List<?> ? (List<?>) result : null;
    }

    @SuppressWarnings("unchecked")
    private static List<String> chatLog(UUID player) {
        Object log = invokeStatic("chatLog", new Class<?>[] { UUID.class }, player);
        return log instanceof List<?> ? (List<String>) log : new ArrayList<>();
    }

    private static int chatCount(UUID player) {
        return chatLog(player).size();
    }

    private static void clearChat(UUID player) {
        List<String> current = chatLog(player);
        if (!current.isEmpty()) {
            List<String> observed = observedChat.computeIfAbsent(player, key -> new ArrayList<>());
            observed.addAll(current);
        }
        invokeStatic("clearChatLog", new Class<?>[] { UUID.class }, player);
    }

    private static Pose pose(ServerPlayer player) {
        return new Pose(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
    }

    private static boolean matches(Pose a, double x, double y, double z, float yaw, float pitch) {
        return a != null && Math.abs(a.x - x) <= TOL && Math.abs(a.y - y) <= TOL && Math.abs(a.z - z) <= TOL
                && Math.abs(a.yaw - yaw) <= TOL && Math.abs(a.pitch - pitch) <= TOL;
    }

    private static boolean matches(Pose a, Pose b) {
        return b != null && matches(a, b.x, b.y, b.z, b.yaw, b.pitch);
    }

    private static long parseLong(String text) {
        try {
            return Long.parseLong(text);
        } catch (Throwable ignored) {
            return -999999L;
        }
    }

    private static void readConfig() {
        expirySeconds = 60;
        for (Path file : new Path[] { Paths.get("run", "config", "tpa.json"), Paths.get("config", "tpa.json") }) {
            try {
                if (!Files.exists(file)) continue;
                String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                java.util.regex.Matcher matcher = java.util.regex.Pattern
                        .compile("\"expirySeconds\"\\s*:\\s*(-?\\d+)").matcher(json);
                if (matcher.find()) expirySeconds = Integer.parseInt(matcher.group(1));
                break;
            } catch (Throwable ignored) {
                // Defaults apply.
            }
        }
    }

    // ---------------- exactly one canonical result line ----------------

    private static void check(boolean ok, String token) {
        if (ok) {
            passed.add(token);
        } else {
            failed.add(token);
            System.out.println("[TPA_PROBE] check_failed " + token);
        }
    }

    private static synchronized void finish() {
        if (finished) return;
        finished = true;
        if (!unregisterTestPlayers()) check(false, "cleanup_failed");
        boolean pass = failed.isEmpty() && passed.size() >= 12;
        String verdict = pass ? "PASS" : "FAIL";
        StringBuilder line = new StringBuilder("TPA_TEST_RESULT: ").append(verdict)
                .append(" cell=").append(cell)
                .append(" product=").append(productPresent)
                .append(" checks=").append(passed.size() + failed.size())
                .append(" failures=").append(failed.size());
        if (!failed.isEmpty()) line.append(" reason=").append(String.join(",", failed));
        for (String token : passed) line.append(' ').append(token);
        String result = line.toString();
        write(result);
        System.out.println("[TPA_PROBE] " + result);
    }

    private static void write(String line) {
        byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
        Set<Path> targets = new LinkedHashSet<>();
        targets.add(Paths.get("tpa-result.txt"));
        targets.add(Paths.get("run", "tpa-result.txt"));
        Path cwd = Paths.get("").toAbsolutePath().normalize();
        targets.add(cwd.resolve("tpa-result.txt"));
        targets.add(cwd.resolve("run").resolve("tpa-result.txt"));
        for (Path target : targets) {
            try {
                Path parent = target.getParent();
                if (parent != null) Files.createDirectories(parent);
                Files.write(target, bytes);
            } catch (Throwable ignored) {
                // stdout is the fallback signal.
            }
        }
    }

    private static void cleanResults() {
        for (Path file : new Path[] {
                Paths.get("tpa-result.txt"), Paths.get("run", "tpa-result.txt"),
                Paths.get("").toAbsolutePath().normalize().resolve("tpa-result.txt"),
                Paths.get("").toAbsolutePath().normalize().resolve("run").resolve("tpa-result.txt") }) {
            try {
                Files.deleteIfExists(file);
            } catch (Throwable ignored) {
                // Best effort.
            }
        }
    }

    private static final class Pose {
        final double x;
        final double y;
        final double z;
        final float yaw;
        final float pitch;

        Pose(double x, double y, double z, float yaw, float pitch) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
        }
    }
}
