package com.frameflags.tpa;

import com.frameflags.tpa.core.TpaPose;
import com.frameflags.tpa.core.TpaRequest;
import com.frameflags.tpa.core.TpaResult;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Brigadier adapter for TPA requests. No permission gate, one message per outcome. */
public final class TpaCommands {
    private static final SuggestionProvider<CommandSourceStack> PLAYER_NAMES = (context, builder) -> {
        for (ServerPlayer player : context.getSource().getServer().getPlayerList().getPlayers()) {
            builder.suggest(player.getGameProfile().getName());
        }
        return builder.buildFuture();
    };

    private TpaCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("tpa")
                .then(Commands.argument("target", StringArgumentType.word()).suggests(PLAYER_NAMES)
                        .executes(context -> request(context, false))));

        dispatcher.register(Commands.literal("tpahere")
                .then(Commands.argument("target", StringArgumentType.word()).suggests(PLAYER_NAMES)
                        .executes(context -> request(context, true))));

        dispatcher.register(decideTree("tpaccept", "accept"));
        dispatcher.register(Commands.literal("tpacceptall").executes(context -> decide(context, "accept", "all")));

        dispatcher.register(decideTree("tpdeny", "deny"));
        dispatcher.register(Commands.literal("tpdenyall").executes(context -> decide(context, "deny", "all")));

        dispatcher.register(decideTree("tpadeny", "deny"));
        dispatcher.register(Commands.literal("tpadenyall").executes(context -> decide(context, "deny", "all")));

        dispatcher.register(decideTree("tpacancel", "cancel"));
        dispatcher.register(Commands.literal("tpacancelall").executes(context -> decide(context, "cancel", "all")));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> decideTree(String name, String action) {
        return Commands.literal(name)
                .executes(context -> decide(context, action, null))
                .then(Commands.argument("target", StringArgumentType.word()).suggests(PLAYER_NAMES)
                        .executes(context -> decide(context, action, StringArgumentType.getString(context, "target"))));
    }

    private static int request(CommandContext<CommandSourceStack> context, boolean here) {
        CommandSourceStack source = context.getSource();
        ServerPlayer requester = player(source);
        if (requester == null) {
            source.sendFailure(Component.literal("Only players can use this command."));
            return 0;
        }
        MinecraftServer server = source.getServer();
        int tick = server.getTickCount();
        String name = StringArgumentType.getString(context, "target");
        ServerPlayer target = Tpa.resolveTarget(server, name);
        if (target == null) {
            Tpa.message(requester, ChatFormatting.RED + missingPlayerText(requester, server, name), tick);
            return 0;
        }
        if (target.getUUID().equals(requester.getUUID())) {
            Tpa.message(requester, ChatFormatting.RED + "You cannot send a teleport request to yourself.", tick);
            return 0;
        }

        TpaPose snapshot = null;
        if (here) {
            snapshot = new TpaPose(requester.getX(), requester.getY(), requester.getZ(),
                    requester.getYRot(), requester.getXRot(),
                    requester.level().dimension().location().toString());
        }
        long deadline = tick + Math.max(1L, (long) Tpa.config().expirySeconds) * 20L;
        String targetName = target.getGameProfile().getName();
        TpaResult result = Tpa.registry().request(requester.getUUID(), target.getUUID(), targetName,
                here ? TpaRequest.Kind.TO_REQUESTER : TpaRequest.Kind.TO_TARGET,
                snapshot, deadline, Tpa.config().maxOutgoing);

        if (result == TpaResult.DUPLICATE) {
            Tpa.message(requester, ChatFormatting.RED + "You already have a pending request to " + targetName + ".", tick);
            return 0;
        }
        if (result == TpaResult.SELF_REQUEST || result == TpaResult.OUTGOING_LIMIT) {
            Tpa.message(requester, ChatFormatting.RED + "Teleport request not sent.", tick);
            return 0;
        }
        if (result != TpaResult.OK) {
            Tpa.message(requester, ChatFormatting.RED + "Could not send teleport request.", tick);
            return 0;
        }

        Tpa.message(requester, ChatFormatting.GREEN + (here
                ? "Requested " + targetName + " to teleport to you."
                : "Requested to teleport to " + targetName + "."), tick);
        Tpa.message(target, ChatFormatting.AQUA + requester.getGameProfile().getName() + (here
                ? " wants you to teleport to them." : " wants to teleport to you.")
                + " Use /tpaccept or /tpdeny.", tick);
        return 1;
    }

    private static int decide(CommandContext<CommandSourceStack> context, String action, String arg) {
        CommandSourceStack source = context.getSource();
        ServerPlayer self = player(source);
        if (self == null) {
            source.sendFailure(Component.literal("Only players can use this command."));
            return 0;
        }
        MinecraftServer server = source.getServer();
        int tick = server.getTickCount();
        boolean outbound = "cancel".equals(action);
        boolean all = false;
        UUID filter = null;
        if (arg != null) {
            if ("all".equalsIgnoreCase(arg) || "*".equals(arg)) {
                all = true;
            } else {
                ServerPlayer named = Tpa.resolveTarget(server, arg);
                if (named == null) {
                    Tpa.message(self, ChatFormatting.RED + missingPlayerText(self, server, arg), tick);
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
            Tpa.message(self, ChatFormatting.RED + emptyText, tick);
            return 0;
        }

        Map<UUID, Integer> summary = new HashMap<UUID, Integer>();
        int handled = 0;
        for (TpaRequest candidate : selected) {
            TpaRequest request = Tpa.registry().remove(candidate.id);
            if (request == null) continue;
            ServerPlayer requester = Tpa.findPlayer(server, request.requester);
            ServerPlayer target = Tpa.findPlayer(server, request.target);
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
                    TpaPose live = new TpaPose(target.getX(), target.getY(), target.getZ(),
                            target.getYRot(), target.getXRot(),
                            target.level().dimension().location().toString());
                    Tpa.teleport(requester, live, server);
                    teleported = true;
                }
            }
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
                ServerPlayer recipient = Tpa.findPlayer(server, entry.getKey());
                if (recipient != null) {
                    Tpa.message(recipient, ChatFormatting.GREEN + "" + entry.getValue()
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
            return ChatFormatting.GREEN + (teleported ? "Teleport request accepted." : "Request accepted, but the destination is unavailable.");
        }
        if ("cancel".equals(action)) return ChatFormatting.YELLOW + "Your teleport request to " + other + " was cancelled.";
        return ChatFormatting.RED + "Your teleport request to " + other + " was denied.";
    }

    private static String targetText(String action, String other, boolean teleported) {
        if ("accept".equals(action)) {
            return ChatFormatting.GREEN + (teleported ? "Accepted teleport request from " + other + "." : "Request accepted, but the destination is unavailable.");
        }
        if ("cancel".equals(action)) return ChatFormatting.YELLOW + "Teleport request from " + other + " was cancelled.";
        return ChatFormatting.RED + "Teleport request from " + other + " was denied.";
    }

    private static String targetName(ServerPlayer player, TpaRequest request) {
        return player == null ? request.targetName : player.getGameProfile().getName();
    }

    private static String requesterName(ServerPlayer player, TpaRequest request) {
        return player == null ? "A player" : player.getGameProfile().getName();
    }

    private static String missingPlayerText(ServerPlayer self, MinecraftServer server, String name) {
        return Tpa.hasOtherPlayers(self, server) ? "No such player: " + name + "." : "No other players online.";
    }

    private static ServerPlayer player(CommandSourceStack source) {
        return source.getEntity() instanceof ServerPlayer ? (ServerPlayer) source.getEntity() : null;
    }
}
