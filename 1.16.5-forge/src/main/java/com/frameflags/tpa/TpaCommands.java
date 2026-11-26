package com.frameflags.tpa;

import com.frameflags.tpa.core.TpaPose;
import com.frameflags.tpa.core.TpaRequest;
import com.frameflags.tpa.core.TpaResult;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.command.CommandSource;
import net.minecraft.command.Commands;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TextFormatting;

/** Brigadier adapter for Forge 1.16.5. No op, cheats or permission node required. */
public final class TpaCommands {
    private static final SuggestionProvider<CommandSource> PLAYER_NAMES = (context, builder) -> {
        for (ServerPlayerEntity player : context.getSource().getServer().getPlayerList().getPlayers()) {
            builder.suggest(player.getName().getString());
        }
        return builder.buildFuture();
    };

    private TpaCommands() {
    }

    public static void register(CommandDispatcher<CommandSource> dispatcher) {
        dispatcher.register(Commands.literal("tpa")
                .executes(ctx -> usage(ctx, "tpa"))
                .then(Commands.argument("target", StringArgumentType.word()).suggests(PLAYER_NAMES)
                        .executes(ctx -> request(ctx, false))));

        dispatcher.register(Commands.literal("tpahere")
                .executes(ctx -> usage(ctx, "tpahere"))
                .then(Commands.argument("target", StringArgumentType.word()).suggests(PLAYER_NAMES)
                        .executes(ctx -> request(ctx, true))));

        dispatcher.register(decideTree("tpaccept", "accept"));
        dispatcher.register(Commands.literal("tpacceptall").executes(ctx -> decide(ctx, "accept", "all")));

        dispatcher.register(decideTree("tpdeny", "deny"));
        dispatcher.register(Commands.literal("tpdenyall").executes(ctx -> decide(ctx, "deny", "all")));

        dispatcher.register(decideTree("tpadeny", "deny"));
        dispatcher.register(Commands.literal("tpadenyall").executes(ctx -> decide(ctx, "deny", "all")));

        dispatcher.register(decideTree("tpacancel", "cancel"));
        dispatcher.register(Commands.literal("tpacancelall").executes(ctx -> decide(ctx, "cancel", "all")));
    }

    private static LiteralArgumentBuilder<CommandSource> decideTree(String name, String action) {
        return Commands.literal(name)
                .executes(ctx -> decide(ctx, action, null))
                .then(Commands.argument("target", StringArgumentType.word()).suggests(PLAYER_NAMES)
                        .executes(ctx -> decide(ctx, action, StringArgumentType.getString(ctx, "target"))));
    }

    private static ServerPlayerEntity player(CommandContext<CommandSource> ctx) {
        Entity entity = ctx.getSource().getEntity();
        return entity instanceof ServerPlayerEntity ? (ServerPlayerEntity) entity : null;
    }

    private static int usage(CommandContext<CommandSource> ctx, String name) {
        CommandSource source = ctx.getSource();
        ServerPlayerEntity self = player(ctx);
        MinecraftServer server = source.getServer();
        int tick = (int) Tpa.tick();
        if (!Tpa.hasOtherPlayers(self, server)) {
            Tpa.message(self, "No other players online.", tick);
        } else {
            Tpa.message(self, "Usage: /" + name + " <player>", tick);
        }
        return 0;
    }

    private static int request(CommandContext<CommandSource> ctx, boolean here) {
        CommandSource source = ctx.getSource();
        ServerPlayerEntity requester = player(ctx);
        if (requester == null) {
            source.sendFailure(new StringTextComponent("Only players can use this command."));
            return 0;
        }
        MinecraftServer server = source.getServer();
        int tick = (int) Tpa.tick();
        String name = StringArgumentType.getString(ctx, "target");
        ServerPlayerEntity target = Tpa.resolveTarget(server, name);
        if (target == null) {
            Tpa.message(requester, TextFormatting.RED + missingPlayerText(requester, server, name), tick);
            return 0;
        }
        if (target.getUUID().equals(requester.getUUID())) {
            Tpa.message(requester, TextFormatting.RED + "You cannot send a teleport request to yourself.", tick);
            return 0;
        }

        TpaPose snapshot = here ? pose(requester) : null;
        long deadline = Tpa.tick() + Math.max(1L, (long) Tpa.config().expirySeconds) * 20L;
        String targetName = target.getName().getString();
        TpaResult result = Tpa.registry().request(requester.getUUID(), target.getUUID(), targetName,
                here ? TpaRequest.Kind.TO_REQUESTER : TpaRequest.Kind.TO_TARGET,
                snapshot, deadline, Tpa.config().maxOutgoing);

        if (result == TpaResult.DUPLICATE) {
            Tpa.message(requester, TextFormatting.RED + "You already have a pending request to " + targetName + ".", tick);
            return 0;
        }
        if (result == TpaResult.SELF_REQUEST || result == TpaResult.OUTGOING_LIMIT) {
            Tpa.message(requester, TextFormatting.RED + "Teleport request not sent.", tick);
            return 0;
        }
        if (result != TpaResult.OK) {
            Tpa.message(requester, TextFormatting.RED + "Could not send teleport request.", tick);
            return 0;
        }

        Tpa.message(requester, TextFormatting.GREEN + (here
                ? "Requested " + targetName + " to teleport to you."
                : "Requested to teleport to " + targetName + "."), tick);
        Tpa.message(target, TextFormatting.AQUA + requester.getName().getString() + (here
                ? " wants you to teleport to them." : " wants to teleport to you.")
                + " Use /tpaccept or /tpdeny.", tick);
        return 1;
    }

    private static int decide(CommandContext<CommandSource> ctx, String action, String arg) {
        CommandSource source = ctx.getSource();
        ServerPlayerEntity self = player(ctx);
        if (self == null) {
            source.sendFailure(new StringTextComponent("Only players can use this command."));
            return 0;
        }
        MinecraftServer server = source.getServer();
        int tick = (int) Tpa.tick();
        boolean outbound = "cancel".equals(action);
        boolean all = false;
        UUID filter = null;
        if (arg != null) {
            if ("all".equalsIgnoreCase(arg) || "*".equals(arg)) {
                all = true;
            } else {
                ServerPlayerEntity named = Tpa.resolveTarget(server, arg);
                if (named == null) {
                    Tpa.message(self, TextFormatting.RED + missingPlayerText(self, server, arg), tick);
                    return 0;
                }
                filter = named.getUUID();
            }
        }

        List<TpaRequest> candidates = outbound
                ? Tpa.registry().outgoing(self.getUUID())
                : Tpa.registry().inbound(self.getUUID());
        List<TpaRequest> selected = new ArrayList<TpaRequest>();
        for (TpaRequest request : candidates) {
            if (filter != null) {
                UUID other = outbound ? request.target : request.requester;
                if (!filter.equals(other)) continue;
            }
            selected.add(request);
            if (!all) break;
        }
        if (selected.isEmpty()) {
            String emptyText = Tpa.hasOtherPlayers(self, server)
                    ? (outbound ? "No outgoing teleport requests." : "No pending teleport requests.")
                    : "No other players online.";
            Tpa.message(self, TextFormatting.RED + emptyText, tick);
            return 0;
        }

        Map<UUID, Integer> summary = new HashMap<UUID, Integer>();
        int handled = 0;
        for (TpaRequest candidate : selected) {
            // Peek first: never lose a request when the destination cannot be resolved.
            TpaRequest request = candidate;
            if (request == null) continue;
            ServerPlayerEntity requester = Tpa.findPlayer(server, request.requester);
            ServerPlayerEntity target = Tpa.findPlayer(server, request.target);
            boolean teleported = false;
            if ("accept".equals(action)) {
                if (request.isHere()) {
                    // /tpahere: the accepter goes to the requester's stored snapshot.
                    if (target != null && request.snapshot != null) {
                        Tpa.teleport(target, request.snapshot, server);
                        teleported = true;
                    }
                } else if (requester != null && target != null) {
                    // /tpa: resolve the target's LIVE pose at accept time.
                    TpaPose live = pose(target);
                    Tpa.teleport(requester, live, server);
                    teleported = true;
                }
            }
            if (teleported || !"accept".equals(action)) Tpa.registry().remove(request.id);
            String requesterText = requesterText(action, targetName(requester, request), teleported);
            String targetText = targetText(action, requesterName(requester, request), teleported);
            if (all) {
                bump(summary, request.requester);
                bump(summary, request.target);
            } else {
                if (requester != null) Tpa.message(requester, requesterText, tick);
                if (target != null) Tpa.message(target, targetText, tick);
            }
            handled++;
        }
        if (all) {
            String verb = "accept".equals(action) ? "accepted" : ("cancel".equals(action) ? "cancelled" : "denied");
            for (Map.Entry<UUID, Integer> entry : summary.entrySet()) {
                ServerPlayerEntity recipient = Tpa.findPlayer(server, entry.getKey());
                if (recipient != null) {
                    Tpa.message(recipient, TextFormatting.GREEN + "" + entry.getValue()
                            + " teleport request" + (entry.getValue() == 1 ? "" : "s") + " " + verb + ".", tick);
                }
            }
        }
        return handled;
    }

    private static void bump(Map<UUID, Integer> map, UUID id) {
        Integer value = map.get(id);
        map.put(id, value == null ? 1 : value + 1);
    }

    private static String requesterText(String action, String other, boolean teleported) {
        if ("accept".equals(action)) {
            return TextFormatting.GREEN + (teleported ? "Teleport request accepted." : "Request accepted, but the destination is unavailable.");
        }
        if ("cancel".equals(action)) return TextFormatting.YELLOW + "Your teleport request to " + other + " was cancelled.";
        return TextFormatting.RED + "Your teleport request to " + other + " was denied.";
    }

    private static String targetText(String action, String other, boolean teleported) {
        if ("accept".equals(action)) {
            return TextFormatting.GREEN + (teleported ? "Accepted teleport request from " + other + "." : "Request accepted, but the destination is unavailable.");
        }
        if ("cancel".equals(action)) return TextFormatting.YELLOW + "Teleport request from " + other + " was cancelled.";
        return TextFormatting.RED + "Teleport request from " + other + " was denied.";
    }

    private static String targetName(ServerPlayerEntity player, TpaRequest request) {
        return player == null ? request.targetName : player.getName().getString();
    }

    private static String requesterName(ServerPlayerEntity player, TpaRequest request) {
        return player == null ? "A player" : player.getName().getString();
    }

    private static String missingPlayerText(ServerPlayerEntity self, MinecraftServer server, String name) {
        return Tpa.hasOtherPlayers(self, server) ? "No such player: " + name + "." : "No other players online.";
    }

    /** Exact source pose: yaw/pitch are copied verbatim into the snapshot or live destination. */
    private static TpaPose pose(ServerPlayerEntity player) {
        return new TpaPose(player.getX(), player.getY(), player.getZ(),
                player.yRot, player.xRot, dimensionKey(player));
    }

    private static String dimensionKey(ServerPlayerEntity player) {
        try {
            return player.level.dimension().location().toString();
        } catch (RuntimeException ignored) {
            return "minecraft:overworld";
        }
    }
}
