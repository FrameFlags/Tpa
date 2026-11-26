package com.frameflags.tpa_test;

import com.mojang.authlib.GameProfile;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.phys.Vec3;

/**
 * In-game behavior probe. All state changes go through the real command dispatcher as either the
 * joined player or a fabricated server player. Product state is inspected reflectively so the
 * probe still loads when the product jar is absent. Fabricated players are registered through the
 * product's probe-only hook and are never inserted into PlayerList (they have no connection).
 */
public final class TpaProbe {
    private static final double TOL = 0.01D;
    private static final Path ROOT_RESULT = Path.of("tpa-result.txt");
    private static final Path RUN_RESULT = Path.of("run/tpa-result.txt");
    private static final String[] REQUIRED = {
            "request_ok", "self_rejected", "no_such_player", "duplicate_rejected",
            "accept_ok", "tpahere_ok", "snapshot_ok", "deny_ok", "cancel_ok",
            "cancelall_ok", "outgoing_limit", "expiry_ok", "no_spam"
    };

    private static String cell = "unknown";
    private static Class<?> product;
    private static boolean productPresent;
    private static boolean finished;
    private static UUID user;
    private static int startTick;
    private static int phase;
    private static int expiryDeadline;
    private static int baselineUser = -1;
    private static int baselineBot = -1;
    private static boolean spamStable = true;
    private static boolean expiryRequestOk;
    private static boolean expiryEarly;
    private static Pose expiryUser;
    private static Pose expiryBot;
    private static final List<FakePlayer> BOTS = new ArrayList<>();
    /** Players whose original gravity was disabled by the probe; restored in cleanup(). */
    private static final List<ServerPlayer> GRAVITY_CHANGED = new ArrayList<>();
    private static final List<String> PASSED = new ArrayList<>();
    private static final List<String> FAILED = new ArrayList<>();

    private TpaProbe() {
    }

    public static void bootstrap(String cellId, Path gameDir) {
        if (finished) return;
        cell = cellId;
        delete(ROOT_RESULT);
        delete(RUN_RESULT);
        try {
            product = Class.forName("com.frameflags.tpa.Tpa");
            productPresent = true;
        } catch (Throwable ignored) {
            productPresent = false;
        }
        if (!productPresent) {
            check(false, "no_product");
            finish();
            return;
        }
        boolean ready = false;
        try {
            Path config = gameDir.resolve("config").resolve("tpa.json");
            Files.createDirectories(config.getParent());
            Files.writeString(config, "{\n  \"expirySeconds\": 2,\n  \"maxOutgoing\": 5\n}\n");
            call("init", new Class<?>[] { Path.class }, gameDir);
            ready = registry() != null;
        } catch (Throwable failure) {
            System.out.println("[TPA_PROBE] config/setup failure: " + failure);
        }
        if (!ready) {
            check(false, "setup");
            finish();
        }
    }

    public static void onJoin(ServerPlayer player, int tick) {
        if (finished || !productPresent || user != null || player == null) return;
        user = player.getUUID();
        startTick = tick;
        try {
            ServerLevel level = (ServerLevel) player.level();
            MinecraftServer server = player.level().getServer();
            for (int i = 0; i < 6; i++) {
                FakePlayer bot = new FakePlayer(server, level, "TpaBot" + i);
                registerBot(bot, server);
                BOTS.add(bot);
            }
            clear(user);
        } catch (Throwable failure) {
            System.out.println("[TPA_PROBE] fake player failure: " + failure);
            check(false, "setup");
            finish();
        }
    }

    public static void onTick(MinecraftServer server, int tick) {
        if (finished || !productPresent || user == null || server == null || BOTS.size() < 6) return;
        ServerPlayer real = server.getPlayerList().getPlayer(user);
        if (real == null) return;
        int elapsed = tick - startTick;
        if (elapsed < 2) return;
        if (elapsed > 2400) {
            check(false, "timeout");
            finish();
            return;
        }
        try {
            switch (phase) {
                case 0: phase0(server, real); break;
                case 1: phase1(server, real); break;
                case 2: phase2(server, real); break;
                case 3: phase3(server, real); break;
                case 4: phase4(server, real); break;
                case 5: phase5(server, real); break;
                case 6: phase6(server, real); break;
                case 7: phase7(server, real, tick); break;
                case 8: phase8(server, real, tick); break;
                default: break;
            }
        } catch (Throwable failure) {
            System.out.println("[TPA_PROBE] phase " + phase + " failure: " + failure);
            check(false, "phase" + phase);
            finish();
        }
    }

    // ---------- behavior phases ----------

    private static void phase0(MinecraftServer server, ServerPlayer real) {
        FakePlayer bot = BOTS.get(0);
        ServerLevel level = (ServerLevel) real.level();
        move(real, new Pose(100.5D, 64.5D, 100.5D, 90.0F, -10.0F), level);
        move(bot, new Pose(200.5D, 64.5D, 200.5D, -45.0F, 5.0F), level);
        for (FakePlayer fake : BOTS) clear(fake.getUUID());
        clear(user);

        int rc = dispatch(server, real, "tpa TpaBot0");
        check(rc > 0 && inbound(bot.getUUID()).size() == 1 && chat(user) == 1, "request_ok");

        // Baseline must be taken AFTER the successful request, which stays pending.
        int before = registrySize();
        clear(user);
        rc = dispatch(server, real, "tpa " + real.getGameProfile().name());
        check(rc == 0 && chat(user) == 1 && registrySize() == before, "self_rejected");

        clear(user);
        rc = dispatch(server, real, "tpa NoSuchPlayer_TPA");
        check(rc == 0 && chat(user) == 1 && registrySize() == before, "no_such_player");

        clear(user);
        rc = dispatch(server, real, "tpa TpaBot0");
        check(rc == 0 && chat(user) == 1 && inbound(bot.getUUID()).size() == 1, "duplicate_rejected");
        phase = 1;
    }

    private static void phase1(MinecraftServer server, ServerPlayer real) {
        FakePlayer bot = BOTS.get(0);
        Pose live = pose(bot);
        clear(user);
        clear(bot.getUUID());
        int rc = dispatch(server, bot, "tpaccept");
        check(rc > 0 && matches(pose(real), live) && matches(pose(bot), live)
                && chat(user) == 1 && chat(bot.getUUID()) == 1
                && inbound(bot.getUUID()).isEmpty(), "accept_ok");
        phase = 2;
    }

    private static void phase2(MinecraftServer server, ServerPlayer real) {
        FakePlayer bot = BOTS.get(0);
        ServerLevel level = (ServerLevel) real.level();
        move(bot, new Pose(210.5D, 70.5D, 210.5D, 10.0F, 20.0F), level);
        Pose snapshot = new Pose(110.5D, 64.5D, 110.5D, 90.0F, -10.0F);
        move(real, snapshot, level);
        clear(user);
        clear(bot.getUUID());
        int created = dispatch(server, real, "tpahere TpaBot0");
        boolean requestOk = created > 0 && inbound(bot.getUUID()).size() == 1;

        Pose after = new Pose(300.5D, 100.5D, 300.5D, 180.0F, 0.0F);
        move(real, after, level);
        clear(user);
        clear(bot.getUUID());
        int rc = dispatch(server, bot, "tpaccept");
        check(requestOk && rc > 0 && matches(pose(bot), snapshot)
                && chat(user) == 1 && chat(bot.getUUID()) == 1, "tpahere_ok");
        check(matches(pose(bot), snapshot) && !matches(pose(bot), after)
                && matches(pose(real), after) && inbound(bot.getUUID()).isEmpty(), "snapshot_ok");
        phase = 3;
    }

    private static void phase3(MinecraftServer server, ServerPlayer real) {
        FakePlayer bot = BOTS.get(0);
        clear(user);
        clear(bot.getUUID());
        int rc = dispatch(server, real, "tpa TpaBot0");
        clear(user);
        clear(bot.getUUID());
        Pose beforeUser = pose(real);
        Pose beforeBot = pose(bot);
        rc = dispatch(server, bot, "tpdeny");
        check(rc > 0 && inbound(bot.getUUID()).isEmpty() && matches(pose(real), beforeUser)
                && matches(pose(bot), beforeBot) && chat(user) == 1
                && chat(bot.getUUID()) == 1, "deny_ok");
        phase = 4;
    }

    private static void phase4(MinecraftServer server, ServerPlayer real) {
        FakePlayer bot = BOTS.get(0);
        clear(user);
        clear(bot.getUUID());
        int rc = dispatch(server, real, "tpa TpaBot0");
        clear(user);
        clear(bot.getUUID());
        Pose beforeUser = pose(real);
        Pose beforeBot = pose(bot);
        rc = dispatch(server, real, "tpacancel");
        check(rc > 0 && outgoing(user).isEmpty() && matches(pose(real), beforeUser)
                && matches(pose(bot), beforeBot) && chat(user) == 1
                && chat(bot.getUUID()) == 1, "cancel_ok");
        phase = 5;
    }

    private static void phase5(MinecraftServer server, ServerPlayer real) {
        dispatch(server, real, "tpa TpaBot0");
        dispatch(server, real, "tpa TpaBot1");
        clear(user);
        Pose before = pose(real);
        int rc = dispatch(server, real, "tpacancelall");
        check(rc > 0 && outgoing(user).isEmpty() && matches(pose(real), before)
                && chat(user) == 1, "cancelall_ok");
        phase = 6;
    }

    private static void phase6(MinecraftServer server, ServerPlayer real) {
        dispatch(server, real, "tpacancelall");
        for (int i = 0; i < 5; i++) dispatch(server, real, "tpa TpaBot" + i);
        int rc = dispatch(server, real, "tpa TpaBot5");
        List<Object> out = outgoing(user);
        boolean oldest = false;
        boolean newest = false;
        for (Object request : out) {
            UUID target = targetOf(request);
            if (target == null) continue;
            if (target.equals(BOTS.get(0).getUUID())) oldest = true;
            if (target.equals(BOTS.get(5).getUUID())) newest = true;
        }
        check(rc > 0 && out.size() == 5 && !oldest && newest, "outgoing_limit");
        dispatch(server, real, "tpacancelall");
        phase = 7;
    }

    private static void phase7(MinecraftServer server, ServerPlayer real, int tick) {
        FakePlayer bot = BOTS.get(0);
        clear(user);
        clear(bot.getUUID());
        int rc = dispatch(server, real, "tpa TpaBot0");
        clear(user);
        clear(bot.getUUID());
        expiryRequestOk = rc > 0 && inbound(bot.getUUID()).size() == 1;
        expiryDeadline = tick + 40;
        expiryUser = pose(real);
        expiryBot = pose(bot);
        expiryEarly = false;
        spamStable = true;
        baselineUser = -1;
        baselineBot = -1;
        phase = 8;
    }

    private static void phase8(MinecraftServer server, ServerPlayer real, int tick) {
        FakePlayer bot = BOTS.get(0);
        if (tick < expiryDeadline + 3) {
            if (tick == expiryDeadline - 6) {
                baselineUser = chat(user);
                baselineBot = chat(bot.getUUID());
            }
            if (tick == expiryDeadline - 2) {
                if (baselineUser >= 0 && baselineBot >= 0
                        && (chat(user) != baselineUser || chat(bot.getUUID()) != baselineBot)) {
                    spamStable = false;
                }
                // A correct request must still be pending two ticks before its 40-tick deadline.
                if (!expiryRequestOk || inbound(bot.getUUID()).size() != 1
                        || chat(user) != 0 || chat(bot.getUUID()) != 0) {
                    expiryEarly = true;
                }
            }
            return;
        }
        check(!expiryEarly && expiryRequestOk && registrySize() == 0 && matches(pose(real), expiryUser)
                && matches(pose(bot), expiryBot) && chat(user) == 1
                && chat(bot.getUUID()) == 1, "expiry_ok");
        check(spamStable && !repeats(user) && !repeats(bot.getUUID()), "no_spam");
        finish();
    }

    // ---------- dispatcher and state observation ----------

    private static int dispatch(MinecraftServer server, ServerPlayer player, String command) {
        try {
            return server.getCommands().getDispatcher().execute(command, player.createCommandSourceStack());
        } catch (Throwable failure) {
            System.out.println("[TPA_PROBE] dispatch '" + command + "' failed: " + failure);
            return -1;
        }
    }

    private static Object call(String name, Class<?>[] types, Object... args) {
        try {
            Method method = product.getMethod(name, types);
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

    private static Object registry() {
        return call("registry", new Class<?>[0]);
    }

    private static int registrySize() {
        Object registry = registry();
        Object value = registry == null ? null : invoke(registry, "size", new Class<?>[0]);
        return value instanceof Number ? ((Number) value).intValue() : -1;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> side(UUID player, boolean outgoing) {
        Object registry = registry();
        Object value = registry == null ? null
                : invoke(registry, outgoing ? "outgoing" : "inbound", new Class<?>[] { UUID.class }, player);
        return value instanceof List<?> ? (List<Object>) value : new ArrayList<>();
    }

    private static List<Object> outgoing(UUID player) {
        return side(player, true);
    }

    private static List<Object> inbound(UUID player) {
        return side(player, false);
    }

    private static UUID targetOf(Object request) {
        try {
            return (UUID) request.getClass().getField("target").get(request);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> log(UUID player) {
        Object value = call("chatLog", new Class<?>[] { UUID.class }, player);
        return value instanceof List<?> ? (List<String>) value : new ArrayList<>();
    }

    private static int chat(UUID player) {
        return log(player).size();
    }

    private static void clear(UUID player) {
        call("clearChatLog", new Class<?>[] { UUID.class }, player);
    }

    private static boolean repeats(UUID player) {
        List<String> entries = log(player);
        for (int i = 1; i < entries.size(); i++) {
            String prev = entries.get(i - 1);
            String curr = entries.get(i);
            int p = prev.indexOf('|');
            int c = curr.indexOf('|');
            if (p < 0 || c < 0) continue;
            if (prev.substring(p + 1).equals(curr.substring(c + 1))) {
                long delta = number(curr.substring(0, c)) - number(prev.substring(0, p));
                if (Math.abs(delta) <= 1) return true;
            }
        }
        return false;
    }

    private static long number(String text) {
        try {
            return Long.parseLong(text);
        } catch (Throwable ignored) {
            return -999999L;
        }
    }

    private static void move(ServerPlayer player, Pose pose, ServerLevel level) {
        // Headless CI servers let entities fall between probe ticks; pin the pose for the run.
        // Original gravity is restored in cleanup() for both the real player and fake players.
        if (player != null && !player.isNoGravity()) {
            player.setNoGravity(true);
            GRAVITY_CHANGED.add(player);
        }
        player.teleportTo(level, pose.x, pose.y, pose.z, Set.of(), pose.yaw, pose.pitch, false);
        player.setDeltaMovement(Vec3.ZERO);
    }

    private static Pose pose(ServerPlayer player) {
        return new Pose(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
    }

    private static boolean matches(Pose a, Pose b) {
        return a != null && b != null
                && Math.abs(a.x - b.x) <= TOL && Math.abs(a.y - b.y) <= TOL && Math.abs(a.z - b.z) <= TOL
                && Math.abs(a.yaw - b.yaw) <= TOL && Math.abs(a.pitch - b.pitch) <= TOL;
    }

    /** Registers a fabricated player via the product hook; never writes into PlayerList. */
    private static void registerBot(FakePlayer fake, MinecraftServer server) throws Exception {
        call("registerTestPlayer", new Class<?>[] { ServerPlayer.class }, fake);
        Object found = call("findPlayer", new Class<?>[] { MinecraftServer.class, UUID.class },
                server, fake.getUUID());
        if (found != fake) throw new IllegalStateException("test player hook did not register " + fake.getUUID());
    }

    private static void cleanup() {
        try {
            try {
                for (ServerPlayer player : GRAVITY_CHANGED) {
                    try {
                        player.setNoGravity(false);
                    } catch (Throwable ignored) {
                        // Best effort; the verdict is already decided.
                    }
                }
            } finally {
                GRAVITY_CHANGED.clear();
                call("unregisterTestPlayers", new Class<?>[0]);
            }
        } catch (Throwable ignored) {
            // Best-effort cleanup; verdict is already decided.
        }
        BOTS.clear();
    }

    // ---------- exact one canonical result ----------

    private static void check(boolean ok, String token) {
        if (ok && !FAILED.contains(token)) {
            if (!PASSED.contains(token)) PASSED.add(token);
        } else if (!ok && !FAILED.contains(token)) {
            FAILED.add(token);
            PASSED.remove(token);
        }
    }

    private static synchronized void finish() {
        if (finished) return;
        finished = true;
        cleanup();
        if (productPresent) {
            for (String token : REQUIRED) {
                if (!PASSED.contains(token) && !FAILED.contains(token)) FAILED.add(token);
            }
        }
        String verdict = FAILED.isEmpty() ? "PASS" : "FAIL";
        StringBuilder line = new StringBuilder("TPA_TEST_RESULT: ").append(verdict)
                .append(" cell=").append(cell)
                .append(" product=").append(productPresent)
                .append(" checks=").append(PASSED.size() + FAILED.size())
                .append(" failures=").append(FAILED.size());
        if (!FAILED.isEmpty()) line.append(" reason=").append(String.join(",", FAILED));
        for (String token : PASSED) line.append(' ').append(token);
        if (!productPresent) line.append(" limit_rejected=false");
        String result = line.toString();
        write(ROOT_RESULT, result);
        write(RUN_RESULT, result);
        System.out.println("[TPA_PROBE] " + result);
    }

    private static void write(Path path, String text) {
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(path, text + "\n");
        } catch (Throwable ignored) {
            // stdout is the fallback signal.
        }
    }

    private static void delete(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (Throwable ignored) {
            // Best effort.
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

    /** Fabricated server player: no network connection, messages and teleports stay local. */
    private static final class FakePlayer extends ServerPlayer {
        FakePlayer(MinecraftServer server, ServerLevel level, String name) {
            super(server, level, new GameProfile(UUID.randomUUID(), name), ClientInformation.createDefault());
            setPos(0.0D, 0.0D, 0.0D);
        }

        @Override
        public void sendSystemMessage(Component component) {
            // Intentionally swallowed: a fabricated player has no connection.
        }

        @Override
        public void sendSystemMessage(Component component, boolean overlay) {
            // Intentionally swallowed: a fabricated player has no connection.
        }

        @Override
        public boolean teleportTo(ServerLevel level, double x, double y, double z,
                                  Set<Relative> relatives, float yaw, float pitch, boolean unknown) {
            setPos(x, y, z);
            setYRot(yaw);
            setXRot(pitch);
            setDeltaMovement(Vec3.ZERO);
            return true;
        }
    }
}
