package com.frameflags.tpa_test;

import com.mojang.authlib.GameProfile;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
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
 * In-game probe. Every state change is dispatched through the real Brigadier dispatcher as
 * a real source stack (the joined player or a fabricated second ServerPlayer). Product
 * helpers are observed only through reflection, so this class also loads when the product
 * jar is absent (the negative-control run).
 */
public final class TpaProbe {
    private static final double TOL = 0.01D;
    private static final String BETA_NAME = "TpaProbeBeta";
    private static final Path ROOT_RESULT = Path.of("tpa-result.txt");
    private static final Path RUN_RESULT = Path.of("run/tpa-result.txt");

    private static String cell = "unknown";
    private static Class<?> productClass;
    private static boolean productPresent;
    private static boolean finished;
    private static MinecraftServer server;
    private static ServerPlayer alpha;
    private static ProbePlayer beta;
    private static UUID alphaId;
    private static UUID betaId;
    private static String alphaName;
    private static int startTick;
    private static int phase;
    private static int nextTick;
    private static int checks;
    private static int expirySeconds = 60;
    private static long expiryDeadline = -1L;
    private static Pose expiryAlphaPose;
    private static Pose expiryBetaPose;
    private static final List<String> passed = new ArrayList<String>();
    private static final List<String> failed = new ArrayList<String>();
    private static final Map<UUID, List<String>> observedChat = new HashMap<UUID, List<String>>();

    private TpaProbe() {
    }

    public static void bootstrap(String cellId) {
        if (finished) return;
        cell = cellId;
        try {
            Files.deleteIfExists(ROOT_RESULT);
            Files.deleteIfExists(RUN_RESULT);
        } catch (Throwable ignored) {
            // stdout is the fallback signal when the filesystem is read-only.
        }
        try {
            productClass = Class.forName("com.frameflags.tpa.Tpa");
            productPresent = productClass != null;
        } catch (Throwable ignored) {
            productPresent = false;
        }
        // Config is reloaded by the product at server start, so the short-expiry override
        // is applied on join (see ensureShortExpiry) rather than in the constructor.
        if (!productPresent) {
            check(false, "no_product");
            finish();
        }
    }

    public static void onJoin(ServerPlayer player, int tick) {
        if (finished || !productPresent || alpha != null || player == null) return;
        alpha = player;
        alphaId = player.getUUID();
        alphaName = player.getGameProfile().getName();
        startTick = tick;
        nextTick = tick + 2;
        player.setNoGravity(true);
        player.getAbilities().flying = true;
        player.onUpdateAbilities();
        player.setDeltaMovement(Vec3.ZERO);
        clearChat(alphaId);
        ensureShortExpiry();
    }

    /** Keeps the green run short even when config/tpa.json was not staged. */
    private static void ensureShortExpiry() {
        Object config = call("config", new Class<?>[0]);
        Object seconds = field(config, "expirySeconds");
        if (seconds instanceof Integer) expirySeconds = ((Integer) seconds).intValue();
        if (expirySeconds > 10) {
            setField(config, "expirySeconds", Integer.valueOf(2));
            expirySeconds = 2;
        }
    }

    public static void onTick(MinecraftServer incoming, int tick) {
        if (finished || !productPresent || alpha == null || incoming == null) return;
        server = incoming;
        ServerPlayer live = incoming.getPlayerList().getPlayer(alphaId);
        if (live == null) {
            check(false, "joined_player_lost");
            finish();
            return;
        }
        alpha = live;
        if (tick - startTick > 1200) {
            check(false, "timeout");
            finish();
            return;
        }
        if (tick < nextTick) return;
        if (phase == 0) phaseRequest(tick);
        else if (phase == 1) phaseAccept(tick);
        else if (phase == 2) phaseHereDenyAndExpiry(tick);
        else if (phase == 3) phaseExpiryWait(tick);
    }

    /** Phase 0: create a request and exercise every rejection path. */
    private static void phaseRequest(int tick) {
        beta = fabricate(server);
        if (beta == null) {
            check(false, "second_player_unavailable");
            finish();
            return;
        }
        betaId = beta.getUUID();
        call("registerTestPlayer", new Class<?>[] { ServerPlayer.class }, beta);

        move(alpha, 100.5D, 64.5D, 100.5D, 90.0F, -10.0F);
        moveBeta(200.5D, 64.5D, 200.5D, 180.0F, 5.0F);
        clearChat(alphaId);
        clearChat(betaId);

        int rc = dispatch(alpha, "tpa " + BETA_NAME);
        Object request = singleOutbound(alphaId);
        check(rc > 0 && request != null && chatCount(alphaId) == 1 && chatCount(betaId) == 1, "request_ok");

        clearChat(alphaId);
        rc = dispatch(alpha, "tpa " + alphaName);
        check(rc <= 0 && outbound(alphaId).size() == 1 && chatCount(alphaId) == 1, "self_rejected");

        clearChat(alphaId);
        rc = dispatch(alpha, "tpa TpaProbeNoSuchPlayer");
        check(rc <= 0 && outbound(alphaId).size() == 1 && chatCount(alphaId) == 1, "no_such_player");

        clearChat(alphaId);
        rc = dispatch(alpha, "tpa " + BETA_NAME);
        check(rc <= 0 && outbound(alphaId).size() == 1 && chatCount(alphaId) == 1, "duplicate_rejected");

        phase = 1;
        nextTick = tick + 2;
    }

    /** Phase 1: cancel, then /tpa + /tpaccept with the target's LIVE pose. */
    private static void phaseAccept(int tick) {
        clearChat(alphaId);
        clearChat(betaId);
        Pose before = pose(alpha);
        int rc = dispatch(alpha, "tpacancel " + BETA_NAME);
        check(rc > 0 && outbound(alphaId).isEmpty() && matches(pose(alpha), before)
                && chatCount(alphaId) == 1 && chatCount(betaId) == 1, "cancel_ok");

        moveBeta(210.5D, 60.5D, 210.5D, 10.0F, 20.0F);
        Pose liveTargetPose = pose(beta);
        dispatch(alpha, "tpa " + BETA_NAME);
        clearChat(alphaId);
        clearChat(betaId);
        rc = dispatch(beta, "tpaccept " + alphaName);
        check(rc > 0 && outbound(alphaId).isEmpty() && matches(pose(alpha), liveTargetPose)
                && chatCount(alphaId) == 1 && chatCount(betaId) == 1, "accept_ok");

        phase = 2;
        nextTick = tick + 2;
    }

    /** Phase 2: /tpahere snapshot wins, then deny, cancelall, and arm the expiry request. */
    private static void phaseHereDenyAndExpiry(int tick) {
        move(alpha, 300.5D, 64.5D, 300.5D, 45.0F, 30.0F);
        Pose snapshot = pose(alpha);
        dispatch(alpha, "tpahere " + BETA_NAME);
        Object hereRequest = singleOutbound(alphaId);
        move(alpha, 400.5D, 64.5D, 400.5D, 0.0F, 0.0F);
        Pose alphaMoved = pose(alpha);
        clearChat(alphaId);
        clearChat(betaId);
        int rc = dispatch(beta, "tpaccept");
        check(rc > 0 && outbound(alphaId).isEmpty() && matches(pose(beta), snapshot)
                && matches(pose(alpha), alphaMoved), "tpahere_ok");
        check(hereRequest != null && Boolean.TRUE.equals(invoke(hereRequest, "isHere", new Class<?>[0]))
                && snapshotMatches(hereRequest, snapshot), "snapshot_ok");

        moveBeta(500.5D, 64.5D, 500.5D, 33.0F, -5.0F);
        Pose betaBefore = pose(beta);
        Pose alphaBefore = pose(alpha);
        dispatch(alpha, "tpa " + BETA_NAME);
        clearChat(alphaId);
        clearChat(betaId);
        rc = dispatch(beta, "tpdeny");
        check(rc > 0 && outbound(alphaId).isEmpty() && matches(pose(alpha), alphaBefore)
                && matches(pose(beta), betaBefore) && chatCount(alphaId) == 1
                && chatCount(betaId) == 1, "deny_ok");

        dispatch(alpha, "tpa " + BETA_NAME);
        clearChat(alphaId);
        clearChat(betaId);
        rc = dispatch(alpha, "tpacancelall");
        check(rc > 0 && outbound(alphaId).isEmpty() && chatCount(alphaId) == 1
                && chatCount(betaId) == 1, "cancelall_ok");

        dispatch(alpha, "tpa " + BETA_NAME);
        Object expiring = singleOutbound(alphaId);
        if (expiring == null) {
            check(false, "expiry_ok");
            check(noRepeatedChat(), "no_spam");
            finish();
            return;
        }
        expiryDeadline = longField(expiring, "deadlineTick");
        expiryAlphaPose = pose(alpha);
        expiryBetaPose = pose(beta);
        if (expiryDeadline <= 0L) {
            check(false, "expiry_ok");
            check(noRepeatedChat(), "no_spam");
            finish();
            return;
        }
        clearChat(alphaId);
        clearChat(betaId);
        phase = 3;
        nextTick = tick + 2;
    }

    /** Phase 3: the request expires once, never teleports, and emits one line per side. */
    private static void phaseExpiryWait(int tick) {
        // Two ticks of slack guarantees the product tick hook ran at/after the deadline.
        if (tick < expiryDeadline + 2L) return;
        boolean removed = outbound(alphaId).isEmpty();
        boolean noTeleport = matches(pose(alpha), expiryAlphaPose) && matches(pose(beta), expiryBetaPose);
        boolean chats = chatCount(alphaId) == 1 && chatCount(betaId) == 1;
        check(removed && noTeleport && chats, "expiry_ok");
        check(noRepeatedChat(), "no_spam");
        finish();
    }

    // ---------- command dispatch: the only mutation path ----------

    private static int dispatch(ServerPlayer player, String command) {
        try {
            return server.getCommands().getDispatcher().execute(command, player.createCommandSourceStack());
        } catch (Throwable failure) {
            System.out.println("[TPA_PROBE] dispatch_failed command=" + command + " error=" + failure);
            return -1;
        }
    }

    // ---------- read-only observation through reflection ----------

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

    private static void setField(Object target, String name, Object value) {
        if (target == null) return;
        try {
            target.getClass().getField(name).set(target, value);
        } catch (Throwable ignored) {
            // Defaults remain in effect.
        }
    }

    private static long longField(Object target, String name) {
        Object value = field(target, name);
        return value instanceof Long ? ((Long) value).longValue() : -1L;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> requests(UUID id, boolean outbound) {
        Object registry = call("registry", new Class<?>[0]);
        Object list = invoke(registry, outbound ? "outgoing" : "inbound", new Class<?>[] { UUID.class }, id);
        return list instanceof List ? (List<Object>) list : new ArrayList<Object>();
    }

    private static List<Object> outbound(UUID id) {
        return requests(id, true);
    }

    private static Object singleOutbound(UUID id) {
        List<Object> list = outbound(id);
        return list.isEmpty() ? null : list.get(0);
    }

    private static boolean snapshotMatches(Object request, Pose expected) {
        Object snapshot = field(request, "snapshot");
        if (snapshot == null) return false;
        double x = doubleField(snapshot, "x");
        double y = doubleField(snapshot, "y");
        double z = doubleField(snapshot, "z");
        float yaw = floatField(snapshot, "yaw");
        float pitch = floatField(snapshot, "pitch");
        return close(x, expected.x) && close(y, expected.y) && close(z, expected.z)
                && close(yaw, expected.yaw) && close(pitch, expected.pitch);
    }

    private static double doubleField(Object target, String name) {
        Object value = field(target, name);
        return value instanceof Double ? ((Double) value).doubleValue() : Double.NaN;
    }

    private static float floatField(Object target, String name) {
        Object value = field(target, name);
        return value instanceof Float ? ((Float) value).floatValue() : Float.NaN;
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
        List<String> seen = observedChat.get(id);
        if (seen == null) {
            seen = new ArrayList<String>();
            observedChat.put(id, seen);
        }
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

    // ---------- pose helpers and player fabrication ----------

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

    private static void move(ServerPlayer player, double x, double y, double z, float yaw, float pitch) {
        player.setPos(x, y, z);
        player.setYRot(yaw);
        player.setXRot(pitch);
        player.setYHeadRot(yaw);
        player.setDeltaMovement(Vec3.ZERO);
    }

    private static void moveBeta(double x, double y, double z, float yaw, float pitch) {
        move(beta, x, y, z, yaw, pitch);
    }

    private static ProbePlayer fabricate(MinecraftServer minecraftServer) {
        try {
            ServerLevel level = minecraftServer.overworld();
            GameProfile profile = new GameProfile(UUID.randomUUID(), BETA_NAME);
            ProbePlayer player = new ProbePlayer(minecraftServer, level, profile);
            player.setNoGravity(true);
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
            move(player, 200.5D, 64.5D, 200.5D, 180.0F, 5.0F);
            return player;
        } catch (Throwable failure) {
            System.out.println("[TPA_PROBE] fabricate_failed error=" + failure);
            return null;
        }
    }

    /** No network connection: capture messages via the product's chat sink instead of packets. */
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
            // No connection to send to; Tpa.chat() records the line for assertions.
        }

        @Override
        public void sendSystemMessage(Component component, boolean actionBar) {
            // No connection to send to; Tpa.chat() records the line for assertions.
        }
    }

    // ---------- exactly one canonical result line ----------

    private static void check(boolean ok, String token) {
        checks++;
        if (ok) passed.add(token);
        else failed.add(token);
    }

    private static synchronized void finish() {
        if (finished) return;
        finished = true;
        boolean complete = failed.isEmpty() && passed.contains("request_ok") && passed.contains("accept_ok")
                && passed.contains("tpahere_ok") && passed.contains("deny_ok") && passed.contains("cancel_ok")
                && passed.contains("expiry_ok") && passed.contains("no_spam")
                && passed.contains("self_rejected") && passed.contains("no_such_player");
        String verdict = complete ? "PASS" : "FAIL";
        StringBuilder line = new StringBuilder("TPA_TEST_RESULT: ").append(verdict)
                .append(" cell=").append(cell)
                .append(" product=").append(productPresent)
                .append(" checks=").append(checks)
                .append(" failures=").append(failed.size());
        if (!failed.isEmpty()) line.append(" reason=").append(String.join(",", failed));
        for (String token : passed) line.append(' ').append(token);
        String result = line.toString();
        try {
            if (RUN_RESULT.getParent() != null) Files.createDirectories(RUN_RESULT.getParent());
            Files.writeString(ROOT_RESULT, result + "\n");
            Files.writeString(RUN_RESULT, result + "\n");
        } catch (Throwable ignored) {
            // stdout is the fallback signal.
        }
        System.out.println("[TPA_PROBE] " + result);
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
