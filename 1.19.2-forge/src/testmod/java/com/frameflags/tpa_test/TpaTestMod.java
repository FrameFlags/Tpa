package com.frameflags.tpa_test;

import com.mojang.authlib.GameProfile;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * In-game TPA probe for Forge 1.19.2. Fabricated players are exposed to the product through
 * its registerTestPlayer hook and are NEVER inserted into the PlayerList (vanilla broadcasts
 * would NPE on a null connection). A skipped check is never recorded as a pass.
 */
@Mod("tpa_test")
public final class TpaTestMod {
    private static final String PREFIX = "TPA_TEST_RESULT:";
    private static final String PRODUCT = "com.frameflags.tpa.Tpa";
    private static final double TOL = 0.01D;
    private static final int PLAYER_WAIT_TICKS = 60;
    private static final int TIMEOUT_TICKS = 1200;
    private static final int BOT_COUNT = 6;
    private static final String[] REQUIRED = {
            "request_ok", "self_rejected", "no_such_player", "duplicate_rejected",
            "accept_ok", "tpahere_ok", "snapshot_ok", "deny_ok", "cancel_ok",
            "outgoing_limit", "cancelall_ok", "expiry_ok", "no_spam"
    };
    private static final Pose LIVE = new Pose(311.5D, 70.0D, 322.5D, 77.0F, -13.0F);
    private static final Pose SNAP = new Pose(12.5D, 64.0D, 13.5D, 125.0F, 33.0F);
    private static final Pose AWAY_A = new Pose(500.5D, 90.0D, 501.5D, 3.0F, 4.0F);
    private static final Pose AWAY_B = new Pose(600.5D, 100.0D, 601.5D, 5.0F, 6.0F);

    private static Class<?> productClass;
    private static boolean productPresent;
    private static boolean finished;
    private static MinecraftServer server;
    private static Path gameDir;
    private static int launchTick = -1;
    private static int startTick;
    private static int phase;
    private static int phaseTick;
    private static ServerPlayer playerA;
    private static ServerPlayer[] bots;
    private static UUID idA;
    private static final Map<UUID, String> names = new HashMap<UUID, String>();
    private static long expiryDeadline = -1L;
    private static Pose acceptExpected;
    private static Pose expiryA;
    private static Pose expiryB;
    private static boolean expiryEarlyTeleport;
    private static final LinkedHashSet<String> passed = new LinkedHashSet<String>();
    private static final LinkedHashSet<String> failed = new LinkedHashSet<String>();
    private static final Map<UUID, List<String>> observed = new HashMap<UUID, List<String>>();

    public TpaTestMod() {
        MinecraftForge.EVENT_BUS.addListener(this::onServerStarted);
        MinecraftForge.EVENT_BUS.register(this);
        try {
            productClass = Class.forName(PRODUCT);
            productPresent = true;
        } catch (Throwable ignored) {
            productPresent = false;
        }
        try {
            gameDir = Paths.get("").toAbsolutePath().normalize();
            cleanResults();
        } catch (Throwable ignored) {
            // Best effort.
        }
        if (!productPresent) {
            check(false, "no_product");
            emit();
        }
    }

    private void onServerStarted(ServerStartedEvent event) {
        server = event.getServer();
        try {
            gameDir = server.getServerDirectory().toPath().toAbsolutePath().normalize();
        } catch (Throwable ignored) {
            // Keep the CWD fallback.
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || finished || !productPresent || server == null) return;
        int tick = server.getTickCount();
        if (launchTick < 0) launchTick = tick;
        if (tick - startTick > TIMEOUT_TICKS && startTick > 0) {
            check(false, "timeout");
            emit();
            return;
        }

        if (playerA == null) {
            if (tick - launchTick < PLAYER_WAIT_TICKS) return;
            playerA = fabricate("TpaProbe_A");
            if (playerA == null) return;
            idA = playerA.getUUID();
            pin(playerA);
            registerHook(playerA);
            bots = new ServerPlayer[BOT_COUNT];
            for (int i = 0; i < bots.length; i++) {
                bots[i] = fabricate("TpaProbe_Bot" + i);
                if (bots[i] == null) {
                    check(false, "fabrication_failed");
                    emit();
                    return;
                }
                pin(bots[i]);
                registerHook(bots[i]);
                clearChat(bots[i].getUUID());
            }
            clearChat(idA);
            ensureConfig();
            startTick = tick;
            phaseTick = tick;
            phase = 0;
            System.out.println("[TPA_PROBE] step=players a=" + profileName(playerA) + " bots=" + bots.length);
            return;
        }
        if (tick - phaseTick < 2) return;
        try {
            step();
        } catch (Throwable error) {
            System.out.println("[TPA_PROBE] step_error phase=" + phase + " error=" + error);
            failed.add("phase_" + phase + "_exception");
            emit();
        }
    }

    private static void step() {
        switch (phase) {
            case 0:
                setPose(playerA, new Pose(100.5D, 64.0D, 100.5D, 10.0F, 20.0F));
                setPose(bots[0], new Pose(200.5D, 64.0D, 200.5D, 30.0F, 40.0F));
                clearChat(idA);
                int rc0 = dispatch(playerA, "tpa " + name(0));
                List<?> created = inbound(bots[0].getUUID());
                RequestView view = created.isEmpty() ? null : new RequestView(created.get(0));
                check(rc0 > 0 && created.size() == 1 && view != null && "TO_TARGET".equals(view.kind)
                        && idA.equals(view.requester) && bots[0].getUUID().equals(view.target)
                        && chatCount(idA) == 1 && chatCount(bots[0].getUUID()) == 1, "request_ok");
                break;
            case 1:
                clearChat(idA);
                dispatch(playerA, "tpa " + name(0));
                check(registrySize() == 1 && chatCount(idA) == 1 && text(idA).contains("already"), "duplicate_rejected");
                clearChat(idA);
                dispatch(playerA, "tpa " + profileName(playerA));
                check(registrySize() == 1 && chatCount(idA) == 1 && text(idA).contains("yourself"), "self_rejected");
                clearChat(idA);
                dispatch(playerA, "tpa NoSuchTpaPlayerXYZ");
                check(registrySize() == 1 && chatCount(idA) == 1 && text(idA).contains("no such"), "no_such_player");
                break;
            case 2:
                setPose(playerA, new Pose(100.5D, 64.0D, 100.5D, 10.0F, 20.0F));
                clearChat(idA);
                int rc2 = dispatch(bots[0], "tpdeny " + profileName(playerA));
                check(rc2 > 0 && registrySize() == 0 && chatCount(idA) == 1, "deny_ok");
                break;
            case 3:
                setPose(bots[0], LIVE);
                dispatch(playerA, "tpa " + name(0));
                acceptExpected = LIVE;
                setPose(playerA, AWAY_A);
                break;
            case 4:
                int rc4 = dispatch(bots[0], "tpaccept");
                check(rc4 > 0 && matches(pose(playerA), acceptExpected) && registrySize() == 0, "accept_ok");
                break;
            case 5:
                setPose(playerA, SNAP);
                dispatch(playerA, "tpahere " + name(0));
                List<?> here = inbound(bots[0].getUUID());
                RequestView hereView = here.isEmpty() ? null : new RequestView(here.get(0));
                boolean snapOk = hereView != null && "TO_REQUESTER".equals(hereView.kind)
                        && hereView.snapshot != null && hereView.snapshot.matches(SNAP);
                check(snapOk, "snapshot_ok");
                setPose(playerA, AWAY_A);
                break;
            case 6:
                setPose(bots[0], AWAY_B);
                int rc6 = dispatch(bots[0], "tpaccept");
                check(rc6 > 0 && matches(pose(bots[0]), SNAP) && matches(pose(playerA), AWAY_A)
                        && registrySize() == 0, "tpahere_ok");
                break;
            case 7:
                Pose before = pose(playerA);
                dispatch(playerA, "tpa " + name(0));
                boolean createdForCancel = registrySize() == 1;
                clearChat(idA);
                int rc7 = dispatch(playerA, "tpacancel " + name(0));
                check(createdForCancel && rc7 > 0 && registrySize() == 0
                        && matches(pose(playerA), before) && chatCount(idA) == 1
                        && text(idA).contains("cancel"), "cancel_ok");
                break;
            case 8:
                for (int i = 0; i < bots.length; i++) dispatch(playerA, "tpa " + name(i));
                List<?> outgoing = outgoing(idA);
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
                break;
            case 9:
                clearChat(idA);
                int rc9 = dispatch(playerA, "tpacancelall");
                check(rc9 > 0 && outgoing(idA).isEmpty(), "cancelall_ok");
                dispatch(playerA, "tpa " + name(0));
                Object expiring = singleOutbound(idA);
                expiryDeadline = expiring == null ? -1L : new RequestView(expiring).deadlineTick;
                expiryA = pose(playerA);
                expiryB = pose(bots[0]);
                expiryEarlyTeleport = false;
                clearChat(idA);
                clearChat(bots[0].getUUID());
                System.out.println("[TPA_PROBE] step=expiry_start deadline=" + expiryDeadline);
                break;
            case 10:
                Long now = callLong("tick");
                if (expiryDeadline <= 0L || now == null || now.longValue() < expiryDeadline + 2L) {
                    if (!matches(pose(playerA), expiryA) || !matches(pose(bots[0]), expiryB)) {
                        expiryEarlyTeleport = true;
                    }
                    return;
                }
                check(registrySize() == 0 && !expiryEarlyTeleport && matches(pose(playerA), expiryA)
                        && matches(pose(bots[0]), expiryB) && text(idA).contains("expired")
                        && text(bots[0].getUUID()).contains("expired"), "expiry_ok");
                break;
            default:
                check(noRepeatedChat(), "no_spam");
                emit();
                return;
        }
        phase++;
        phaseTick = server.getTickCount();
    }

    // ---------------- real dispatcher ----------------

    private static int dispatch(ServerPlayer sender, String command) {
        try {
            return server.getCommands().getDispatcher().execute(command, sender.createCommandSourceStack());
        } catch (Throwable error) {
            System.out.println("[TPA_PROBE] dispatch_failed cmd=" + command + " error=" + error);
            return -1;
        }
    }

    // ---------------- stable fabricated players ----------------

    private static ServerPlayer fabricate(String name) {
        try {
            ServerLevel world = server.getLevel(Level.OVERWORLD);
            if (world == null) return null;
            GameProfile profile = new GameProfile(UUID.randomUUID(), name);
            names.put(profile.getId(), name);
            ProbePlayer player = new ProbePlayer(server, world, profile);
            player.setPos(10.5D, 64.0D, 10.5D);
            return player;
        } catch (Throwable error) {
            System.out.println("[TPA_PROBE] fabricate_failed name=" + name + " error=" + error);
            return null;
        }
    }

    private static void registerHook(ServerPlayer player) {
        if (player == null || productClass == null) return;
        try {
            for (Method method : productClass.getMethods()) {
                if ("registerTestPlayer".equals(method.getName()) && method.getParameterTypes().length == 1) {
                    method.invoke(null, player);
                    return;
                }
            }
        } catch (Throwable ignored) {
            // Older product builds resolve only real server players.
        }
    }

    /** Profile names read from the authlib GameProfile, discovered by return type. */
    private static String profileName(ServerPlayer player) {
        if (player == null) return null;
        String stored = names.get(player.getUUID());
        if (stored != null) return stored;
        try {
            for (Class<?> type = player.getClass(); type != null; type = type.getSuperclass()) {
                for (Method method : type.getDeclaredMethods()) {
                    if (method.getParameterTypes().length != 0) continue;
                    if (!method.getReturnType().getName().endsWith("GameProfile")) continue;
                    method.setAccessible(true);
                    Object profile = method.invoke(player);
                    if (profile == null) continue;
                    Object value = profile.getClass().getMethod("getName").invoke(profile);
                    if (value instanceof String) return (String) value;
                }
            }
        } catch (Throwable ignored) {
            // Fall through to the mapped accessor.
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

    // ---------------- reflective product observation ----------------

    private static Object call(String name, Class<?>[] types, Object... args) {
        try {
            Method method = productClass.getMethod(name, types);
            return method.invoke(null, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Long callLong(String name) {
        Object value = call(name, new Class<?>[0]);
        return value instanceof Long ? (Long) value : null;
    }

    private static Object registry() {
        return call("registry", new Class<?>[0]);
    }

    private static int registrySize() {
        Object reg = registry();
        if (reg == null) return -1;
        try {
            return ((Integer) reg.getClass().getMethod("size").invoke(reg)).intValue();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> inbound(UUID target) {
        Object reg = registry();
        if (reg == null) return Collections.emptyList();
        try {
            Object result = reg.getClass().getMethod("inbound", UUID.class).invoke(reg, target);
            return result instanceof List ? (List<Object>) result : Collections.emptyList();
        } catch (Throwable ignored) {
            return Collections.emptyList();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> outgoing(UUID requester) {
        Object reg = registry();
        if (reg == null) return Collections.emptyList();
        try {
            Object result = reg.getClass().getMethod("outgoing", UUID.class).invoke(reg, requester);
            return result instanceof List ? (List<Object>) result : Collections.emptyList();
        } catch (Throwable ignored) {
            return Collections.emptyList();
        }
    }

    private static Object singleOutbound(UUID id) {
        List<Object> list = outgoing(id);
        return list.isEmpty() ? null : list.get(0);
    }

    @SuppressWarnings("unchecked")
    private static List<String> chatLog(UUID player) {
        Object value = call("chatLog", new Class<?>[] { UUID.class }, player);
        return value instanceof List ? (List<String>) value : Collections.emptyList();
    }

    private static int chatCount(UUID player) {
        return chatLog(player).size();
    }

    private static void clearChat(UUID player) {
        if (player == null) return;
        List<String> seen = observed.get(player);
        if (seen == null) {
            seen = new ArrayList<String>();
            observed.put(player, seen);
        }
        seen.addAll(chatLog(player));
        call("clearChatLog", new Class<?>[] { UUID.class }, player);
    }

    private static String text(UUID player) {
        StringBuilder sb = new StringBuilder();
        for (String line : chatLog(player)) sb.append(line).append('|');
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    private static boolean noRepeatedChat() {
        for (Map.Entry<UUID, List<String>> entry : observed.entrySet()) {
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

    private static void ensureConfig() {
        try {
            Object config = call("config", new Class<?>[0]);
            if (config == null) return;
            Field field = config.getClass().getField("expirySeconds");
            Object current = field.get(config);
            if (current instanceof Integer && ((Integer) current).intValue() > 10) field.set(config, Integer.valueOf(2));
            field = config.getClass().getField("maxOutgoing");
            current = field.get(config);
            if (current instanceof Integer && ((Integer) current).intValue() != 5) field.set(config, Integer.valueOf(5));
        } catch (Throwable ignored) {
            // Defaults apply.
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

    private static final class RequestView {
        final UUID requester;
        final UUID target;
        final String kind;
        final Pose snapshot;
        final long deadlineTick;

        RequestView(Object request) {
            requester = (UUID) field(request, "requester");
            target = (UUID) field(request, "target");
            Object kindValue = field(request, "kind");
            kind = kindValue == null ? "" : kindValue.toString();
            Object poseValue = field(request, "snapshot");
            snapshot = poseValue == null ? null : new Pose(
                    doubleField(poseValue, "x"), doubleField(poseValue, "y"), doubleField(poseValue, "z"),
                    (float) doubleField(poseValue, "yaw"), (float) doubleField(poseValue, "pitch"));
            Object deadline = field(request, "deadlineTick");
            deadlineTick = deadline instanceof Number ? ((Number) deadline).longValue() : -1L;
        }
    }

    private static double doubleField(Object target, String name) {
        Object value = field(target, name);
        return value instanceof Number ? ((Number) value).doubleValue() : Double.NaN;
    }

    // ---------------- pose helpers ----------------

    private static Pose pose(ServerPlayer player) {
        return new Pose(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
    }

    private static void pin(ServerPlayer player) {
        try {
            player.setNoGravity(true);
            player.setDeltaMovement(Vec3.ZERO);
        } catch (Throwable ignored) {
            // Fabricated players still hold their pose.
        }
    }

    private static void setPose(ServerPlayer player, Pose pose) {
        player.setPos(pose.x, pose.y, pose.z);
        player.setYRot((float) pose.yaw);
        player.setXRot((float) pose.pitch);
        player.setDeltaMovement(Vec3.ZERO);
    }

    private static boolean matches(Pose a, Pose b) {
        return a != null && b != null && Math.abs(a.x - b.x) <= TOL && Math.abs(a.y - b.y) <= TOL
                && Math.abs(a.z - b.z) <= TOL && Math.abs(a.yaw - b.yaw) <= TOL
                && Math.abs(a.pitch - b.pitch) <= TOL;
    }

    private static final class ProbePlayer extends ServerPlayer {
        ProbePlayer(MinecraftServer server, ServerLevel level, GameProfile profile) {
            super(server, level, profile, null);
        }

        @Override
        public void teleportTo(ServerLevel level, double x, double y, double z, float yaw, float pitch) {
            setPos(x, y, z);
            setYRot(yaw);
            setXRot(pitch);
            setDeltaMovement(Vec3.ZERO);
        }

        @Override
        public void sendSystemMessage(Component component) {
        }

        @Override
        public void sendSystemMessage(Component component, boolean actionBar) {
        }
    }

    // ---------------- result ----------------

    private static void check(boolean ok, String token) {
        if (ok) passed.add(token);
        else {
            failed.add(token);
            System.out.println("[TPA_PROBE] check_failed " + token);
        }
    }

    private static synchronized void emit() {
        if (finished) return;
        finished = true;
        boolean complete = failed.isEmpty();
        for (String token : REQUIRED) if (!passed.contains(token)) complete = false;
        String verdict = complete ? "PASS" : "FAIL";
        StringBuilder line = new StringBuilder(PREFIX).append(' ').append(verdict)
                .append(" product=").append(productPresent)
                .append(" checks=").append(passed.size() + failed.size())
                .append(" failures=").append(failed.size());
        if (!failed.isEmpty()) line.append(" reason=").append(String.join(",", failed));
        for (String token : REQUIRED) {
            Boolean ok = passed.contains(token) ? Boolean.TRUE : (failed.contains(token) ? Boolean.FALSE : null);
            String state = ok == null ? "skipped" : (ok.booleanValue() ? "ok" : "fail");
            line.append(' ').append(token).append('=').append(state);
            if (ok != null && ok.booleanValue()) line.append(' ').append(token);
        }
        String result = line.toString();
        write(result);
        System.out.println("[TPA_PROBE] " + result);
    }

    private static void write(String line) {
        byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
        Set<Path> targets = new LinkedHashSet<Path>();
        Path cwd = Paths.get("").toAbsolutePath().normalize();
        targets.add(cwd.resolve("tpa-result.txt"));
        targets.add(cwd.resolve("run").resolve("tpa-result.txt"));
        if (gameDir != null) {
            targets.add(gameDir.resolve("tpa-result.txt"));
            if (gameDir.getParent() != null) targets.add(gameDir.getParent().resolve("tpa-result.txt"));
        }
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
        Set<Path> targets = new LinkedHashSet<Path>();
        Path cwd = Paths.get("").toAbsolutePath().normalize();
        targets.add(cwd.resolve("tpa-result.txt"));
        targets.add(cwd.resolve("run").resolve("tpa-result.txt"));
        if (gameDir != null) {
            targets.add(gameDir.resolve("tpa-result.txt"));
            if (gameDir.getParent() != null) targets.add(gameDir.getParent().resolve("tpa-result.txt"));
        }
        for (Path target : targets) {
            try {
                Files.deleteIfExists(target);
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

        boolean matches(Pose other) {
            return other != null && Math.abs(x - other.x) <= TOL && Math.abs(y - other.y) <= TOL
                    && Math.abs(z - other.z) <= TOL && Math.abs(yaw - other.yaw) <= TOL
                    && Math.abs(pitch - other.pitch) <= TOL;
        }
    }
}
