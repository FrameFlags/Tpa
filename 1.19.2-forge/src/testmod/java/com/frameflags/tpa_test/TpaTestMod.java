package com.frameflags.tpa_test;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.event.server.ServerStartedEvent;

/**
 * In-game TPA probe for Forge 1.19.2. Drives the real Brigadier dispatcher as
 * two players; product state is read reflectively so the negative control
 * (product jar absent) still loads and emits one FAIL line.
 */
@Mod("tpa_test")
public final class TpaTestMod {
    private static final String PREFIX = "TPA_TEST_RESULT:";
    private static final String PRODUCT = "com.frameflags.tpa.Tpa";
    private static final double TOL = 0.01D;
    private static final int PLAYER_WAIT_TICKS = 30;
    private static final int TIMEOUT_TICKS = 1200;
    private static final String[] REQUIRED = {
            "request_ok", "self_rejected", "no_such_player", "duplicate_rejected",
            "accept_ok", "tpahere_ok", "deny_ok", "cancel_ok", "expiry_ok", "no_spam"
    };
    private static final Pose START_A = new Pose(100.5D, 64.0D, 100.5D, 10.0F, 20.0F);
    private static final Pose START_B = new Pose(200.5D, 64.0D, 200.5D, 30.0F, 40.0F);
    private static final Pose LIVE = new Pose(311.5D, 70.0D, 322.5D, 77.0F, -13.0F);
    private static final Pose SNAP = new Pose(12.5D, 64.0D, 13.5D, 125.0F, 33.0F);
    private static final Pose AWAY_A = new Pose(500.5D, 90.0D, 501.5D, 3.0F, 4.0F);
    private static final Pose AWAY_B = new Pose(600.5D, 100.0D, 601.5D, 5.0F, 6.0F);

    private static Class<?> productClass;
    private static boolean productPresent;
    private static boolean finished;
    private static boolean spam;
    private static MinecraftServer server;
    private static Path gameDir;
    private static int launchTick = -1;
    private static int startTick;
    private static int phase;
    private static ServerPlayer playerA;
    private static ServerPlayer playerB;
    private static UUID idA;
    private static UUID idB;
    private static long expiryDeadline = -1L;
    private static Pose acceptExpected;
    private static Pose snapExpected;
    private static final Map<String, Boolean> RESULTS = new LinkedHashMap<String, Boolean>();
    private static final Map<UUID, Integer> LOG_CURSOR = new HashMap<UUID, Integer>();
    private static final Map<UUID, String> LAST_TEXT = new HashMap<UUID, String>();
    private static final Map<UUID, Integer> LAST_TICK = new HashMap<UUID, Integer>();

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
            Files.deleteIfExists(gameDir.resolve("tpa-result.txt"));
            Files.deleteIfExists(gameDir.resolve("run").resolve("tpa-result.txt"));
        } catch (Throwable ignored) {
            // Best effort.
        }
        if (!productPresent) {
            for (String token : REQUIRED) RESULTS.put(token, Boolean.FALSE);
            emit("FAIL", "no_product");
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
        watchSpam(tick);

        if (playerA == null) {
            List<ServerPlayer> players = server.getPlayerList().getPlayers();
            if (players != null && !players.isEmpty()) playerA = players.get(0);
            else if (tick - launchTick >= PLAYER_WAIT_TICKS) playerA = fabricate("TpaProbe_A");
            if (playerA == null) return;
            idA = playerA.getUUID();
            if (players != null && players.size() > 1 && players.get(1) != playerA) playerB = players.get(1);
            else playerB = fabricate("TpaProbe_B");
            if (playerB == null) {
                for (String token : REQUIRED) RESULTS.put(token, Boolean.FALSE);
                emit("FAIL", "no_second_player");
                return;
            }
            idB = playerB.getUUID();
            startTick = tick;
            phase = 0;
            System.out.println("[TPA_PROBE] step=players a=" + playerA.getName().getString()
                    + " b=" + playerB.getName().getString() + " fabricatedB=" + (playerB instanceof ProbePlayer));
            return;
        }
        if (tick - startTick < 2) return;
        if (tick - startTick > TIMEOUT_TICKS) {
            emit("FAIL", "timeout_phase_" + phase);
            return;
        }
        try {
            step();
        } catch (Throwable error) {
            System.out.println("[TPA_PROBE] step_error phase=" + phase + " error=" + error);
            emit("FAIL", "exception_phase_" + phase);
        }
    }

    private static void step() {
        switch (phase) {
            case 0:
                setPose(playerA, START_A);
                setPose(playerB, START_B);
                clearLogs();
                dispatch(playerA, "tpa " + playerB.getName().getString());
                List<?> created = inbound(idB);
                RequestView view = created.isEmpty() ? null : new RequestView(created.get(0));
                check("request_ok", created.size() == 1 && view != null
                        && "TO_TARGET".equals(view.kind) && idA.equals(view.requester)
                        && idB.equals(view.target) && count(log(idA)) == 1 && count(log(idB)) == 1);
                phase++;
                break;
            case 1:
                clearLogs();
                dispatch(playerA, "tpa " + playerB.getName().getString());
                check("duplicate_rejected", registrySize() == 1 && count(log(idA)) == 1
                        && joined(log(idA)).contains("already"));
                clearLogs();
                dispatch(playerA, "tpa " + playerA.getName().getString());
                check("self_rejected", registrySize() == 1 && count(log(idA)) == 1
                        && joined(log(idA)).contains("yourself"));
                clearLogs();
                dispatch(playerA, "tpa NoSuchTpaPlayerXYZ");
                check("no_such_player", registrySize() == 1 && count(log(idA)) == 1
                        && joined(log(idA)).contains("No such"));
                phase++;
                break;
            case 2:
                setPose(playerA, START_A);
                clearLogs();
                dispatch(playerB, "tpdeny " + playerA.getName().getString());
                check("deny_ok", registrySize() == 0 && count(log(idB)) == 1
                        && matches(pose(playerA), START_A));
                phase++;
                break;
            case 3:
                setPose(playerB, START_B);
                clearLogs();
                dispatch(playerA, "tpa " + playerB.getName().getString());
                acceptExpected = LIVE;
                setPose(playerB, LIVE);
                phase++;
                break;
            case 4:
                setPose(playerA, AWAY_A);
                clearLogs();
                dispatch(playerB, "tpaccept");
                check("accept_ok", matches(pose(playerA), acceptExpected) && registrySize() == 0
                        && count(log(idB)) == 1);
                phase++;
                break;
            case 5:
                setPose(playerA, SNAP);
                clearLogs();
                dispatch(playerA, "tpahere " + playerB.getName().getString());
                List<?> here = inbound(idB);
                RequestView hereView = here.isEmpty() ? null : new RequestView(here.get(0));
                snapExpected = SNAP;
                boolean snapOk = hereView != null && "TO_REQUESTER".equals(hereView.kind)
                        && hereView.snapshot != null && hereView.snapshot.matches(SNAP);
                check("tpahere_ok", snapOk);
                setPose(playerA, AWAY_A);
                phase++;
                break;
            case 6:
                setPose(playerB, AWAY_B);
                clearLogs();
                dispatch(playerB, "tpaccept");
                check("tpahere_ok", RESULTS.get("tpahere_ok").booleanValue()
                        && matches(pose(playerB), snapExpected) && matches(pose(playerA), AWAY_A)
                        && registrySize() == 0);
                phase++;
                break;
            case 7:
                clearLogs();
                dispatch(playerA, "tpa " + playerB.getName().getString());
                boolean createdForCancel = registrySize() == 1;
                dispatch(playerA, "tpacancel " + playerB.getName().getString());
                check("cancel_ok", createdForCancel && registrySize() == 0
                        && count(log(idA)) == 1 && joined(log(idA)).contains("Cancelled"));
                phase++;
                break;
            case 8:
                clearLogs();
                dispatch(playerA, "tpa " + playerB.getName().getString());
                List<?> pending = inbound(idB);
                if (pending.isEmpty()) {
                    check("expiry_ok", false);
                    phase = 10;
                } else {
                    expiryDeadline = new RequestView(pending.get(0)).deadlineTick;
                    setPose(playerA, AWAY_A);
                    setPose(playerB, AWAY_B);
                    System.out.println("[TPA_PROBE] step=expiry_start deadline=" + expiryDeadline);
                    phase++;
                }
                break;
            case 9:
                Long now = callLong("tick");
                if (now == null || now.longValue() <= expiryDeadline + 2L) return;
                check("expiry_ok", registrySize() == 0
                        && matches(pose(playerA), AWAY_A) && matches(pose(playerB), AWAY_B)
                        && joined(log(idA)).contains("expired") && joined(log(idB)).contains("expired"));
                phase++;
                break;
            case 10:
                check("no_spam", !spam);
                emit(pass() ? "PASS" : "FAIL", null);
                break;
            default:
                emit("FAIL", "bad_phase_" + phase);
        }
    }

    // ---------- real dispatcher; fake players are registered only while executing ----------

    private static void dispatch(ServerPlayer sender, String command) {
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        boolean added = !players.contains(sender);
        if (added) players.add(sender);
        try {
            server.getCommands().getDispatcher().execute(command, sender.createCommandSourceStack());
        } catch (Throwable error) {
            System.out.println("[TPA_PROBE] dispatch_failed cmd=" + command + " error=" + error);
        } finally {
            if (added) players.remove(sender);
        }
    }

    // ---------- reflective product observation ----------

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
    private static List<?> inbound(UUID target) {
        Object reg = registry();
        if (reg == null) return Collections.emptyList();
        try {
            Object result = reg.getClass().getMethod("inbound", UUID.class).invoke(reg, target);
            return result instanceof List<?> ? (List<?>) result : Collections.emptyList();
        } catch (Throwable ignored) {
            return Collections.emptyList();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> log(UUID player) {
        Object value = call("chatLog", new Class<?>[] { UUID.class }, player);
        if (value instanceof List<?>) {
            List<String> copy = new ArrayList<String>();
            for (Object item : (List<?>) value) copy.add(String.valueOf(item));
            return copy;
        }
        return Collections.emptyList();
    }

    private static void clearLogs() {
        call("clearChatLog", new Class<?>[] { UUID.class }, idA);
        call("clearChatLog", new Class<?>[] { UUID.class }, idB);
        LOG_CURSOR.put(idA, Integer.valueOf(0));
        LOG_CURSOR.put(idB, Integer.valueOf(0));
    }

    private static int count(List<String> messages) {
        return messages == null ? 0 : messages.size();
    }

    private static String joined(List<String> messages) {
        StringBuilder sb = new StringBuilder();
        if (messages != null) for (String message : messages) sb.append(message).append('|');
        return sb.toString();
    }

    private static void watchSpam(int tick) {
        if (idA == null || idB == null) return;
        watchOne(idA, tick);
        watchOne(idB, tick);
    }

    private static void watchOne(UUID uuid, int tick) {
        List<String> current = log(uuid);
        Integer cursor = LOG_CURSOR.get(uuid);
        int from = cursor == null ? 0 : cursor.intValue();
        if (current.size() < from) from = 0;
        for (int i = from; i < current.size(); i++) {
            String text = current.get(i);
            String previous = LAST_TEXT.get(uuid);
            Integer previousTick = LAST_TICK.get(uuid);
            if (text.equals(previous) && previousTick != null && tick - previousTick.intValue() <= 1) spam = true;
            LAST_TEXT.put(uuid, text);
            LAST_TICK.put(uuid, Integer.valueOf(tick));
        }
        LOG_CURSOR.put(uuid, Integer.valueOf(current.size()));
    }

    private static final class RequestView {
        final long id;
        final UUID requester;
        final UUID target;
        final String kind;
        final Pose snapshot;
        final long deadlineTick;

        RequestView(Object request) {
            id = fieldLong(request, "id");
            requester = (UUID) field(request, "requester");
            target = (UUID) field(request, "target");
            Object kindValue = field(request, "kind");
            kind = kindValue == null ? "" : kindValue.toString();
            Object poseValue = field(request, "snapshot");
            snapshot = poseValue == null ? null : new Pose(
                    fieldDouble(poseValue, "x"), fieldDouble(poseValue, "y"), fieldDouble(poseValue, "z"),
                    (float) fieldDouble(poseValue, "yaw"), (float) fieldDouble(poseValue, "pitch"));
            deadlineTick = fieldLong(request, "deadlineTick");
        }
    }

    private static Object field(Object object, String name) {
        try {
            return object.getClass().getField(name).get(object);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static long fieldLong(Object object, String name) {
        Object value = field(object, name);
        return value instanceof Number ? ((Number) value).longValue() : -1L;
    }

    private static double fieldDouble(Object object, String name) {
        Object value = field(object, name);
        return value instanceof Number ? ((Number) value).doubleValue() : Double.NaN;
    }

    // ---------- pose helpers ----------

    private static Pose pose(ServerPlayer player) {
        return new Pose(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
    }

    private static void setPose(ServerPlayer player, Pose pose) {
        player.setPos(pose.x, pose.y, pose.z);
        player.setYRot((float) pose.yaw);
        player.setXRot((float) pose.pitch);
        player.setDeltaMovement(Vec3.ZERO);
    }

    private static boolean matches(Pose a, Pose b) {
        return a != null && b != null
                && Math.abs(a.x - b.x) <= TOL && Math.abs(a.y - b.y) <= TOL
                && Math.abs(a.z - b.z) <= TOL && Math.abs(a.yaw - b.yaw) <= TOL
                && Math.abs(a.pitch - b.pitch) <= TOL;
    }

    private static ServerPlayer fabricate(String name) {
        try {
            ServerLevel world = server.getLevel(Level.OVERWORLD);
            if (world == null) return null;
            GameProfile profile = new GameProfile(UUID.randomUUID(), name);
            ProbePlayer player = new ProbePlayer(server, world, profile);
            player.setPos(100.5D, 64.0D, 100.5D);
            return player;
        } catch (Throwable error) {
            System.out.println("[TPA_PROBE] fabricate_failed error=" + error);
            return null;
        }
    }

    private static final class ProbePlayer extends ServerPlayer {
        ProbePlayer(MinecraftServer server, ServerLevel level, GameProfile profile) {
            super(server, level, profile, null);
        }

        @Override
        public void teleportTo(ServerLevel level, double x, double y, double z, float yaw, float pitch) {
            // No connection to packet-teleport: set the exact pose directly.
            setPos(x, y, z);
            setYRot(yaw);
            setXRot(pitch);
            setDeltaMovement(Vec3.ZERO);
        }

        @Override
        public void sendSystemMessage(Component component) {
            // Recorded by Tpa.chat(); no connection to send packets to.
        }

        @Override
        public void sendSystemMessage(Component component, boolean actionBar) {
            // Recorded by Tpa.chat(); no connection to send packets to.
        }
    }

    // ---------- result ----------

    private static void check(String token, boolean ok) {
        RESULTS.put(token, Boolean.valueOf(ok));
        if (!ok) System.out.println("[TPA_PROBE] check_failed " + token);
    }

    private static boolean pass() {
        if (RESULTS.size() < REQUIRED.length) return false;
        for (String token : REQUIRED) {
            Boolean value = RESULTS.get(token);
            if (value == null || !value.booleanValue()) return false;
        }
        return true;
    }

    private static synchronized void emit(String verdict, String reason) {
        if (finished) return;
        finished = true;
        StringBuilder line = new StringBuilder(PREFIX).append(' ').append(verdict)
                .append(" product=").append(productPresent);
        if (reason != null) line.append(" reason=").append(reason);
        List<String> failed = new ArrayList<String>();
        for (String token : REQUIRED) {
            Boolean value = RESULTS.get(token);
            String state = value == null ? "skipped" : (value.booleanValue() ? "ok" : "fail");
            line.append(' ').append(token).append('=').append(state);
            if ("ok".equals(state)) line.append(' ').append(token);
            else failed.add(token);
        }
        if (!failed.isEmpty()) line.append(" failed=").append(join(failed));
        if (!productPresent) line.append(" limit_rejected");
        String result = line.toString();
        write(result);
        System.out.println("[TPA_PROBE] " + result);
    }

    private static String join(List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (String value : values) {
            if (sb.length() > 0) sb.append(',');
            sb.append(value);
        }
        return sb.toString();
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
            return other != null
                    && Math.abs(x - other.x) <= TOL && Math.abs(y - other.y) <= TOL
                    && Math.abs(z - other.z) <= TOL && Math.abs(yaw - other.yaw) <= TOL
                    && Math.abs(pitch - other.pitch) <= TOL;
        }
    }
}
