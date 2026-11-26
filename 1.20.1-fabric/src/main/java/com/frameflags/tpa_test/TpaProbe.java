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
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.phys.Vec3;

/**
 * In-game TPA probe. Every state change goes through the real Brigadier dispatcher, with
 * stable fabricated players registered through the product-side probe hook (never inserted
 * into the PlayerList, whose vanilla broadcasts would NPE on a null connection). Product
 * classes are touched only by reflection so the negative control also loads. A skipped
 * check is never recorded as a pass.
 */
public final class TpaProbe {
    private static final double TOL = 0.01D;
    private static final int BOT_COUNT = 6;
    private static final int SETTLE_TICKS = 20;
    private static final int TIMEOUT_TICKS = 1200;
    private static final String[] REQUIRED = {
            "request_ok", "self_rejected", "no_such_player", "duplicate_rejected",
            "accept_ok", "tpahere_ok", "snapshot_ok", "deny_ok", "cancel_ok",
            "outgoing_limit", "cancelall_ok", "expiry_ok", "no_spam"
    };

    private static String cell = "unknown";
    private static Class<?> productClass;
    private static boolean productPresent;
    private static boolean finished;
    private static MinecraftServer server;
    private static ServerPlayer alpha;
    private static UUID alphaId;
    private static String alphaName;
    private static ServerPlayer[] bots;
    private static final Map<UUID, String> names = new HashMap<UUID, String>();
    private static int bootTick = -1;
    private static int startTick;
    private static int phase;
    private static int phaseTick;
    private static int expirySeconds = 2;
    private static long expiryDeadline = -1L;
    private static Pose expiryAlpha;
    private static Pose expiryBeta;
    private static boolean expiryEarlyTeleport;

    private static final LinkedHashSet<String> passed = new LinkedHashSet<String>();
    private static final LinkedHashSet<String> failed = new LinkedHashSet<String>();
    private static final Map<UUID, List<String>> observedChat = new HashMap<UUID, List<String>>();

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
        if (!productPresent) {
            check(false, "no_product");
            finish();
        }
    }

    /** Joined clients are ignored: fabricated players keep the run deterministic. */
    public static void onJoin(ServerPlayer player, int tick) {
        // No-op: see onTick, which fabricates the stable participant set.
    }

    public static void onTick(MinecraftServer incoming, int tick) {
        if (finished || !productPresent || incoming == null) return;
        server = incoming;
        if (bootTick < 0) bootTick = tick;

        if (alpha == null) {
            // Fabricate a stable primary player; no joined client is required.
            if (tick - bootTick < 100) return;
            alpha = fabricate("TpaProbeAlpha");
            if (alpha == null) {
                check(false, "no_player");
                finish();
                return;
            }
            alphaId = alpha.getUUID();
            alphaName = names.get(alphaId);
            startTick = tick;
            pin(alpha);
            clearChat(alphaId);
            ensureConfig();
            registerHook(alpha);
        }

        if (bots == null) {
            bots = new ServerPlayer[BOT_COUNT];
            for (int i = 0; i < bots.length; i++) {
                bots[i] = fabricate("TpaProbeBot" + i);
                if (bots[i] == null) {
                    check(false, "fabrication_failed");
                    finish();
                    return;
                }
                pin(bots[i]);
                registerHook(bots[i]);
                clearChat(bots[i].getUUID());
            }
            phaseTick = tick;
        }

        if (tick - startTick < SETTLE_TICKS) return;
        if (tick - startTick > TIMEOUT_TICKS) {
            check(false, "timeout");
            finish();
            return;
        }
        if (tick - phaseTick < 2) return;

        switch (phase) {
            case 0:
                phaseValidation();
                break;
            case 1:
                phaseAccept();
                break;
            case 2:
                phaseHere();
                break;
            case 3:
                phaseDeny();
                break;
            case 4:
                phaseLimit();
                break;
            case 5:
                phaseExpiryStart();
                break;
            case 6:
                if (phaseExpiryWait(tick)) break;
                return;
            default:
                phaseFinish();
                return;
        }
        phase++;
        phaseTick = tick;
    }

    // ---------------- phases ----------------

    private static void phaseValidation() {
        clearChat(alphaId);
        int rc = dispatch(alpha, "tpa " + name(0));
        check(rc > 0 && singleOutbound(alphaId) != null
                && chatCount(alphaId) == 1 && chatCount(bots[0].getUUID()) == 1, "request_ok");

        clearChat(alphaId);
        rc = dispatch(alpha, "tpa " + alphaName);
        check(rc <= 0 && outgoing(alphaId).size() == 1 && chatCount(alphaId) == 1, "self_rejected");

        clearChat(alphaId);
        rc = dispatch(alpha, "tpa TpaProbeNoSuchPlayerZZZ");
        check(rc <= 0 && outgoing(alphaId).size() == 1 && chatCount(alphaId) == 1, "no_such_player");

        clearChat(alphaId);
        rc = dispatch(alpha, "tpa " + name(0));
        check(rc <= 0 && outgoing(alphaId).size() == 1 && chatCount(alphaId) == 1, "duplicate_rejected");
    }

    private static void phaseAccept() {
        clearChat(alphaId);
        int rc = dispatch(alpha, "tpacancel " + name(0));
        check(rc > 0 && outgoing(alphaId).isEmpty() && chatCount(alphaId) == 1, "cancel_ok");

        setPose(bots[0], 210.5D, 60.5D, 210.5D, 10.0F, 20.0F);
        Pose targetPose = pose(bots[0]);
        dispatch(alpha, "tpa " + name(0));
        clearChat(alphaId);
        clearChat(bots[0].getUUID());
        rc = dispatch(bots[0], "tpaccept " + alphaName);
        check(rc > 0 && outgoing(alphaId).isEmpty() && matches(pose(alpha), targetPose)
                && chatCount(alphaId) == 1 && chatCount(bots[0].getUUID()) == 1, "accept_ok");
    }

    private static void phaseHere() {
        setPose(alpha, 300.5D, 64.5D, 300.5D, 45.0F, 30.0F);
        Pose snapshot = pose(alpha);
        dispatch(alpha, "tpahere " + name(0));
        Object hereRequest = singleOutbound(alphaId);
        setPose(alpha, 400.5D, 64.5D, 400.5D, 0.0F, 0.0F);
        Pose moved = pose(alpha);
        clearChat(bots[0].getUUID());
        int rc = dispatch(bots[0], "tpaccept");
        check(rc > 0 && outgoing(alphaId).isEmpty() && matches(pose(bots[0]), snapshot)
                && matches(pose(alpha), moved), "tpahere_ok");
        check(hereRequest != null && Boolean.TRUE.equals(invoke(hereRequest, "isHere", new Class<?>[0]))
                && snapshotMatches(hereRequest, snapshot), "snapshot_ok");
    }

    private static void phaseDeny() {
        setPose(bots[0], 500.5D, 64.5D, 500.5D, 33.0F, -5.0F);
        Pose botBefore = pose(bots[0]);
        Pose alphaBefore = pose(alpha);
        dispatch(alpha, "tpa " + name(0));
        clearChat(alphaId);
        clearChat(bots[0].getUUID());
        int rc = dispatch(bots[0], "tpdeny");
        check(rc > 0 && outgoing(alphaId).isEmpty() && matches(pose(alpha), alphaBefore)
                && matches(pose(bots[0]), botBefore) && chatCount(alphaId) == 1
                && chatCount(bots[0].getUUID()) == 1, "deny_ok");
    }

    private static void phaseLimit() {
        for (int i = 0; i < bots.length; i++) dispatch(alpha, "tpa " + name(i));
        List<?> outgoing = outgoing(alphaId);
        boolean oldestEvicted = true;
        boolean newestPresent = false;
        if (outgoing == null) oldestEvicted = false;
        else {
            for (Object request : outgoing) {
                Object target = field(request, "target");
                if (bots[0].getUUID().equals(target)) oldestEvicted = false;
                if (bots[bots.length - 1].getUUID().equals(target)) newestPresent = true;
            }
        }
        check(outgoing != null && outgoing.size() == 5 && oldestEvicted && newestPresent, "outgoing_limit");
    }

    private static void phaseExpiryStart() {
        clearChat(alphaId);
        int rc = dispatch(alpha, "tpacancelall");
        check(rc > 0 && outgoing(alphaId).isEmpty(), "cancelall_ok");

        dispatch(alpha, "tpa " + name(0));
        Object request = singleOutbound(alphaId);
        if (request == null) {
            check(false, "expiry_ok");
            expiryDeadline = -1L;
        } else {
            expiryDeadline = longField(request, "deadlineTick");
        }
        expiryAlpha = pose(alpha);
        expiryBeta = pose(bots[0]);
        expiryEarlyTeleport = false;
        clearChat(alphaId);
        clearChat(bots[0].getUUID());
    }

    private static boolean phaseExpiryWait(int tick) {
        // The 1.20.1 product clocks expiry from the server tick count, not a Tpa.tick() accessor.
        if (expiryDeadline <= 0L || (long) tick < expiryDeadline + 2L) {
            if (!matches(pose(alpha), expiryAlpha) || !matches(pose(bots[0]), expiryBeta)) {
                expiryEarlyTeleport = true;
            }
            return false;
        }
        boolean removed = outgoing(alphaId).isEmpty();
        boolean noTeleport = !expiryEarlyTeleport && matches(pose(alpha), expiryAlpha)
                && matches(pose(bots[0]), expiryBeta);
        boolean chats = chatCount(alphaId) == 1 && chatCount(bots[0].getUUID()) == 1;
        check(removed && noTeleport && chats, "expiry_ok");
        return true;
    }

    private static void phaseFinish() {
        check(noRepeatedChat(), "no_spam");
        finish();
    }

    // ---------------- command dispatch ----------------

    private static int dispatch(ServerPlayer sender, String command) {
        try {
            return server.getCommands().getDispatcher().execute(command, sender.createCommandSourceStack());
        } catch (Throwable failure) {
            System.out.println("[TPA_PROBE] dispatch_failed command=" + command + " error=" + failure);
            return -1;
        }
    }

    // ---------------- stable fabricated players ----------------

    private static ServerPlayer fabricate(String name) {
        try {
            ServerLevel level = server.overworld();
            UUID id = UUID.randomUUID();
            GameProfile profile = new GameProfile(id, name);
            names.put(id, name);
            ProbePlayer player = new ProbePlayer(server, level, profile);
            pin(player);
            setPose(player, 10.5D, 64.5D, 10.5D, 0.0F, 0.0F);
            return player;
        } catch (Throwable failure) {
            System.out.println("[TPA_PROBE] fabricate_failed name=" + name + " error=" + failure);
            return null;
        }
    }

    /** Product-side hook: visible to name/UUID resolution without entering the PlayerList. */
    private static void registerHook(ServerPlayer player) {
        if (player == null) return;
        Object ignored = call("registerTestPlayer", new Class<?>[] { ServerPlayer.class }, player);
    }

    /** Profile names via reflection on the GameProfile, never on remapped Minecraft getters. */
    private static String profileName(ServerPlayer player) {
        if (player == null) return null;
        String stored = names.get(player.getUUID());
        if (stored != null) return stored;
        try {
            for (Class<?> type = player.getClass(); type != null; type = type.getSuperclass()) {
                for (Method method : type.getDeclaredMethods()) {
                    if (method.getParameterCount() != 0) continue;
                    if (!method.getReturnType().getName().endsWith("GameProfile")) continue;
                    method.setAccessible(true);
                    Object profile = method.invoke(player);
                    if (profile == null) continue;
                    Object value = profile.getClass().getMethod("getName").invoke(profile);
                    if (value instanceof String) return (String) value;
                }
            }
        } catch (Throwable ignored) {
            // Fall through to the mapped compile-time accessor.
        }
        try {
            return player.getGameProfile().getName();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String name(int index) {
        return profileName(bots[index]);
    }

    // ---------------- read-only product observation ----------------

    private static Object call(String name, Class<?>[] types, Object... args) {
        try {
            Method method = productClass.getMethod(name, types);
            return method.invoke(null, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) {
        if (target == null) return null;
        try {
            Method method = target.getClass().getMethod(name, types);
            return method.invoke(target, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object field(Object target, String name) {
        if (target == null) return null;
        try {
            return target.getClass().getField(name).get(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static long longField(Object target, String name) {
        Object value = field(target, name);
        return value instanceof Long ? ((Long) value).longValue() : -1L;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> outgoing(UUID id) {
        Object registry = call("registry", new Class<?>[0]);
        Object list = invoke(registry, "outgoing", new Class<?>[] { UUID.class }, id);
        return list instanceof List ? (List<Object>) list : new ArrayList<Object>();
    }

    private static Object singleOutbound(UUID id) {
        List<Object> list = outgoing(id);
        return list.isEmpty() ? null : list.get(0);
    }

    private static boolean snapshotMatches(Object request, Pose expected) {
        Object snapshot = field(request, "snapshot");
        if (snapshot == null) return false;
        return close(doubleField(snapshot, "x"), expected.x) && close(doubleField(snapshot, "y"), expected.y)
                && close(doubleField(snapshot, "z"), expected.z) && close(floatField(snapshot, "yaw"), expected.yaw)
                && close(floatField(snapshot, "pitch"), expected.pitch);
    }

    private static double doubleField(Object target, String name) {
        Object value = field(target, name);
        return value instanceof Number ? ((Number) value).doubleValue() : Double.NaN;
    }

    private static float floatField(Object target, String name) {
        Object value = field(target, name);
        return value instanceof Number ? ((Number) value).floatValue() : Float.NaN;
    }

    @SuppressWarnings("unchecked")
    private static List<String> chatLog(UUID id) {
        Object log = call("chatLog", new Class<?>[] { UUID.class }, id);
        return log instanceof List ? (List<String>) log : new ArrayList<String>();
    }

    private static int chatCount(UUID id) {
        return chatLog(id).size();
    }

    private static void clearChat(UUID id) {
        if (id == null) return;
        List<String> seen = observedChat.computeIfAbsent(id, key -> new ArrayList<String>());
        seen.addAll(chatLog(id));
        call("clearChatLog", new Class<?>[] { UUID.class }, id);
    }

    private static boolean noRepeatedChat() {
        for (Map.Entry<UUID, List<String>> entry : observedChat.entrySet()) {
            List<String> log = new ArrayList<String>(entry.getValue());
            log.addAll(chatLog(entry.getKey()));
            for (int i = 1; i < log.size(); i++) {
                String previous = log.get(i - 1);
                String current = log.get(i);
                int barPrev = previous.indexOf('|');
                int barCur = current.indexOf('|');
                if (barPrev < 0 || barCur < 0) continue;
                if (previous.substring(barPrev + 1).equals(current.substring(barCur + 1))) {
                    long delta = parseLong(current.substring(0, barCur)) - parseLong(previous.substring(0, barPrev));
                    if (Math.abs(delta) <= 1) return false;
                }
            }
        }
        return true;
    }

    private static long parseLong(String text) {
        try {
            return Long.parseLong(text);
        } catch (Throwable ignored) {
            return -999999L;
        }
    }

    /** CI stages a 2s expiry; override an unexpected default so the run stays short. */
    private static void ensureConfig() {
        try {
            Object config = call("config", new Class<?>[0]);
            if (config == null) return;
            java.lang.reflect.Field field = config.getClass().getField("expirySeconds");
            Object current = field.get(config);
            if (current instanceof Integer && ((Integer) current).intValue() > 10) {
                field.set(config, Integer.valueOf(2));
            }
            field = config.getClass().getField("maxOutgoing");
            current = field.get(config);
            if (current instanceof Integer && ((Integer) current).intValue() != 5) {
                field.set(config, Integer.valueOf(5));
            }
            Object seconds = config.getClass().getField("expirySeconds").get(config);
            if (seconds instanceof Integer) expirySeconds = ((Integer) seconds).intValue();
        } catch (Throwable ignored) {
            // Defaults apply.
        }
    }

    // ---------------- pose helpers ----------------

    private static Pose pose(ServerPlayer player) {
        return new Pose(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
    }

    private static boolean matches(Pose a, Pose b) {
        return a != null && b != null && close(a.x, b.x) && close(a.y, b.y) && close(a.z, b.z)
                && close(a.yaw, b.yaw) && close(a.pitch, b.pitch);
    }

    private static boolean close(double a, double b) {
        return Math.abs(a - b) <= TOL;
    }

    private static void pin(ServerPlayer player) {
        try {
            player.setNoGravity(true);
            player.setDeltaMovement(Vec3.ZERO);
        } catch (Throwable ignored) {
            // Fabricated players still have usable abilities.
        }
    }

    private static void setPose(ServerPlayer player, double x, double y, double z, float yaw, float pitch) {
        player.setPos(x, y, z);
        player.setYRot(yaw);
        player.setXRot(pitch);
        player.setYHeadRot(yaw);
        player.setDeltaMovement(Vec3.ZERO);
    }

    /** Unconnected player used only as a command source and teleport target. */
    private static final class ProbePlayer extends ServerPlayer {
        ProbePlayer(MinecraftServer minecraftServer, ServerLevel level, GameProfile profile) {
            super(minecraftServer, level, profile);
        }

        @Override
        public boolean teleportTo(ServerLevel level, double x, double y, double z,
                                  Set<RelativeMovement> movements, float yaw, float pitch) {
            setPos(x, y, z);
            setYRot(yaw);
            setXRot(pitch);
            setYHeadRot(yaw);
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

    // ---------------- exactly one canonical result line ----------------

    private static void check(boolean ok, String token) {
        if (ok) passed.add(token);
        else {
            failed.add(token);
            System.out.println("[TPA_PROBE] check_failed " + token);
        }
    }

    private static synchronized void finish() {
        if (finished) return;
        finished = true;
        boolean complete = failed.isEmpty();
        for (String token : REQUIRED) if (!passed.contains(token)) complete = false;
        String verdict = complete ? "PASS" : "FAIL";
        StringBuilder line = new StringBuilder("TPA_TEST_RESULT: ").append(verdict)
                .append(" cell=").append(cell)
                .append(" product=").append(productPresent)
                .append(" checks=").append(passed.size() + failed.size())
                .append(" failures=").append(failed.size());
        if (!failed.isEmpty()) line.append(" reason=").append(String.join(",", failed));
        for (String token : passed) line.append(' ').append(token);
        // Skipped checks are explicitly marked and never appear as bare passing tokens.
        if (!complete) {
            for (String token : REQUIRED) {
                if (!passed.contains(token) && !failed.contains(token)) line.append(' ').append(token).append("=skipped");
            }
        }
        String result = line.toString();
        write(result);
        System.out.println("[TPA_PROBE] " + result);
    }

    private static void write(String line) {
        byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
        Set<Path> targets = new LinkedHashSet<Path>();
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
