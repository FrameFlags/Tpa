package com.frameflags.tpa;

import com.frameflags.tpa.core.RequestRegistry;
import com.frameflags.tpa.core.TpaConfig;
import com.frameflags.tpa.core.TpaPose;
import com.frameflags.tpa.core.TpaRequest;
import com.frameflags.tpa.core.TpaResult;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Brigadier adapter for /tpa and its accept/deny/cancel family. No permission gate. */
public final class TpaCommands {
    private static final int ACCEPT = 0;
    private static final int DENY = 1;
    private static final int CANCEL = 2;

    private static final SuggestionProvider<CommandSourceStack> PLAYER_NAMES = (context, builder) -> {
        if (context.getSource().getServer() != null) {
            for (ServerPlayer player : context.getSource().getServer().getPlayerList().getPlayers()) {
                builder.suggest(player.getGameProfile().name());
            }
        }
        return builder.buildFuture();
    };

    private TpaCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("tpa")
                .then(Commands.argument("player", StringArgumentType.word()).suggests(PLAYER_NAMES)
                        .executes(context -> request(context, false))));
        dispatcher.register(Commands.literal("tpahere")
                .then(Commands.argument("player", StringArgumentType.word()).suggests(PLAYER_NAMES)
                        .executes(context -> request(context, true))));

        resolve(dispatcher, "tpaccept", "player", ACCEPT);
        dispatcher.register(Commands.literal("tpacceptall").executes(context -> resolve(context, "all", ACCEPT)));
        resolve(dispatcher, "tpdeny", "player", DENY);
        dispatcher.register(Commands.literal("tpdenyall").executes(context -> resolve(context, "all", DENY)));
        resolve(dispatcher, "tpadeny", "player", DENY);
        dispatcher.register(Commands.literal("tpadenyall").executes(context -> resolve(context, "all", DENY)));
        resolve(dispatcher, "tpacancel", "player", CANCEL);
        dispatcher.register(Commands.literal("tpacancelall").executes(context -> resolve(context, "all", CANCEL)));
    }

    private static void resolve(CommandDispatcher<CommandSourceStack> dispatcher, String name,
                                String argument, int mode) {
        dispatcher.register(Commands.literal(name)
                .executes(context -> resolve(context, "", mode))
                .then(Commands.argument(argument, StringArgumentType.word()).suggests(PLAYER_NAMES)
                        .executes(context -> resolve(context, StringArgumentType.getString(context, argument), mode))));
    }

    private static int request(CommandContext<CommandSourceStack> context, boolean here) {
        CommandSourceStack source = context.getSource();
        ServerPlayer requester = source.getEntity() instanceof ServerPlayer ? (ServerPlayer) source.getEntity() : null;
        if (requester == null) {
            Tpa.message(source, null, "Only players can use this command.", false);
            return 0;
        }
        MinecraftServer server = source.getServer();
        String name = StringArgumentType.getString(context, "player");
        if (server == null || !Tpa.hasOtherPlayers(requester, server)) {
            Tpa.message(source, requester, ChatFormatting.RED + "No other players online.", false);
            return 0;
        }
        ServerPlayer target = Tpa.resolveTarget(server, name);
        if (target == null) {
            Tpa.message(source, requester, ChatFormatting.RED + "No such player: " + name, false);
            return 0;
        }
        if (target.getUUID().equals(requester.getUUID())) {
            Tpa.message(source, requester, ChatFormatting.RED + "You cannot send a request to yourself.", false);
            return 0;
        }
        TpaConfig config = Tpa.config();
        TpaPose snapshot = here ? pose(requester) : null;
        long deadline = server.getTickCount() + (long) Math.max(1, config.expirySeconds) * 20L;
        TpaResult result = Tpa.registry().request(requester.getUUID(), target.getUUID(),
                target.getGameProfile().name(),
                here ? TpaRequest.Kind.TO_REQUESTER : TpaRequest.Kind.TO_TARGET,
                snapshot, deadline, config.maxOutgoing);
        if (result == TpaResult.SELF_REQUEST) {
            Tpa.message(source, requester, ChatFormatting.RED + "You cannot send a request to yourself.", false);
            return 0;
        }
        if (result == TpaResult.DUPLICATE) {
            Tpa.message(source, requester, ChatFormatting.RED + "A request to " + target.getGameProfile().name()
                    + " is already pending.", false);
            return 0;
        }
        if (result != TpaResult.OK) {
            Tpa.message(source, requester, ChatFormatting.RED + "Could not create request.", false);
            return 0;
        }
        String verb = here ? "asked " + target.getGameProfile().name() + " to teleport to you"
                : "asked to teleport to " + target.getGameProfile().name();
        Tpa.message(source, requester, ChatFormatting.GREEN + "You " + verb + ".", true);
        Tpa.tell(server, target.getUUID(), ChatFormatting.AQUA + requester.getGameProfile().name()
                + " sent you a teleport request (" + (here ? "tpahere" : "tpa") + ").", server.getTickCount());
        return 1;
    }

    private static int resolve(CommandContext<CommandSourceStack> context, String selector, int mode) {
        CommandSourceStack source = context.getSource();
        ServerPlayer actor = source.getEntity() instanceof ServerPlayer ? (ServerPlayer) source.getEntity() : null;
        if (actor == null) {
            Tpa.message(source, null, "Only players can use this command.", false);
            return 0;
        }
        MinecraftServer server = source.getServer();
        if (server == null) {
            Tpa.message(source, actor, ChatFormatting.RED + "No server available.", false);
            return 0;
        }
        RequestRegistry registry = Tpa.registry();
        List<TpaRequest> pending = mode == CANCEL ? registry.outgoing(actor.getUUID()) : registry.inbound(actor.getUUID());
        List<TpaRequest> chosen = select(server, pending, selector, mode == CANCEL);
        if (chosen.isEmpty()) {
            String what = mode == ACCEPT ? "accept" : mode == DENY ? "deny" : "cancel";
            Tpa.message(source, actor, ChatFormatting.RED + "No pending requests to " + what + ".", false);
            return 0;
        }
        int tick = server.getTickCount();
        int affected = 0;
        for (TpaRequest request : chosen) {
            registry.remove(request.id);
            UUID other = mode == CANCEL ? request.target : request.requester;
            if (mode == CANCEL) {
                Tpa.tell(server, other, ChatFormatting.YELLOW + actor.getGameProfile().name()
                        + " cancelled the teleport request.", tick);
            } else if (mode == DENY) {
                Tpa.tell(server, other, ChatFormatting.RED + actor.getGameProfile().name()
                        + " denied your teleport request.", tick);
            } else if (request.isHere()) {
                ServerPlayer guest = Tpa.findPlayer(server, request.target);
                if (guest != null && request.snapshot != null) {
                    Tpa.teleport(guest, request.snapshot, server);
                    affected++;
                    Tpa.tell(server, guest.getUUID(), ChatFormatting.GREEN + actor.getGameProfile().name()
                            + " accepted your request. Teleporting.", tick);
                } else {
                    Tpa.tell(server, other, ChatFormatting.RED + "The requester is no longer online.", tick);
                }
            } else {
                ServerPlayer requester = Tpa.findPlayer(server, request.requester);
                ServerPlayer target = Tpa.findPlayer(server, request.target);
                if (requester != null && target != null) {
                    // /tpa uses the target's live pose at accept time.
                    Tpa.teleport(requester, pose(target), server);
                    affected++;
                    Tpa.tell(server, requester.getUUID(), ChatFormatting.GREEN + actor.getGameProfile().name()
                            + " accepted your request. Teleporting.", tick);
                } else {
                    Tpa.tell(server, other, ChatFormatting.RED + "That player is no longer online.", tick);
                }
            }
        }
        String summary = mode == ACCEPT ? "Accepted " + affected + " request(s)."
                : mode == DENY ? "Denied " + chosen.size() + " request(s)."
                : "Cancelled " + chosen.size() + " request(s).";
        Tpa.message(source, actor, ChatFormatting.GREEN + summary, true);
        return chosen.size();
    }

    private static List<TpaRequest> select(MinecraftServer server, List<TpaRequest> pending,
                                            String selector, boolean byTarget) {
        List<TpaRequest> chosen = new ArrayList<>();
        String raw = selector == null ? "" : selector.trim();
        boolean all = raw.isEmpty() || raw.equalsIgnoreCase("all") || "*".equals(raw);
        if (all) {
            chosen.addAll(pending);
            return chosen;
        }
        ServerPlayer named = Tpa.resolveTarget(server, raw);
        UUID id = named == null ? null : named.getUUID();
        for (TpaRequest request : pending) {
            UUID candidate = byTarget ? request.target : request.requester;
            if (raw.equalsIgnoreCase(request.targetName)
                    || (id != null && id.equals(candidate))
                    || raw.equalsIgnoreCase(candidate.toString())) {
                chosen.add(request);
                break;
            }
        }
        return chosen;
    }

    private static TpaPose pose(ServerPlayer player) {
        return new TpaPose(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot(),
                player.level().dimension().identifier().toString());
    }
}
