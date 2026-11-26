package com.frameflags.tpa;

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
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Brigadier adapter for the TPA command family. No permission gate anywhere. */
public final class TpaCommands {
    private static final SuggestionProvider<CommandSourceStack> ONLINE = (context, builder) -> {
        for (ServerPlayer player : context.getSource().getServer().getPlayerList().getPlayers()) {
            builder.suggest(player.getGameProfile().getName());
        }
        return builder.buildFuture();
    };

    private TpaCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("tpa")
                .then(Commands.argument("target", StringArgumentType.word()).suggests(ONLINE)
                        .executes(context -> request(context, false))));
        dispatcher.register(Commands.literal("tpahere")
                .then(Commands.argument("target", StringArgumentType.word()).suggests(ONLINE)
                        .executes(context -> request(context, true))));

        dispatcher.register(Commands.literal("tpaccept")
                .executes(context -> accept(context, null))
                .then(Commands.argument("who", StringArgumentType.word())
                        .executes(context -> accept(context, StringArgumentType.getString(context, "who")))));
        dispatcher.register(Commands.literal("tpacceptall").executes(context -> accept(context, "all")));

        for (String alias : new String[] { "tpdeny", "tpadeny" }) {
            dispatcher.register(Commands.literal(alias)
                    .executes(context -> deny(context, null))
                    .then(Commands.argument("who", StringArgumentType.word())
                            .executes(context -> deny(context, StringArgumentType.getString(context, "who")))));
        }
        dispatcher.register(Commands.literal("tpdenyall").executes(context -> deny(context, "all")));
        dispatcher.register(Commands.literal("tpadenyall").executes(context -> deny(context, "all")));

        dispatcher.register(Commands.literal("tpacancel")
                .executes(context -> cancel(context, null))
                .then(Commands.argument("who", StringArgumentType.word())
                        .executes(context -> cancel(context, StringArgumentType.getString(context, "who")))));
        dispatcher.register(Commands.literal("tpacancelall").executes(context -> cancel(context, "all")));
    }

    private static int request(CommandContext<CommandSourceStack> context, boolean here) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        if (!(source.getEntity() instanceof ServerPlayer)) {
            source.sendFailure(Component.literal(ChatFormatting.RED + "Only players can use this command."));
            return 0;
        }
        ServerPlayer player = (ServerPlayer) source.getEntity();
        if (server.getPlayerList().getPlayers().size() <= 1) {
            return fail(player, server, "No other players online.");
        }
        String name = StringArgumentType.getString(context, "target");
        ServerPlayer target = Tpa.resolveTarget(server, name);
        if (target == null) return fail(player, server, "No such player: " + name);
        if (target.getUUID().equals(player.getUUID())) {
            return fail(player, server, "You cannot send a teleport request to yourself.");
        }

        TpaPose snapshot = here ? Tpa.poseOf(player) : null;
        long deadline = server.getTickCount() + Math.max(0, Tpa.config().expirySeconds) * 20L;
        TpaResult result = Tpa.registry().request(player.getUUID(), target.getUUID(),
                target.getGameProfile().getName(),
                here ? TpaRequest.Kind.TO_REQUESTER : TpaRequest.Kind.TO_TARGET,
                snapshot, deadline, Tpa.config().maxOutgoing);
        if (result == TpaResult.SELF_REQUEST) {
            return fail(player, server, "You cannot send a teleport request to yourself.");
        }
        if (result == TpaResult.DUPLICATE) {
            return fail(player, server, "A teleport request to " + name + " is already pending.");
        }
        if (result == TpaResult.OUTGOING_LIMIT) {
            return fail(player, server, "You already have the maximum number of outgoing requests.");
        }
        if (result != TpaResult.OK) return fail(player, server, "Could not create the teleport request.");

        int tick = server.getTickCount();
        String toRequester = ChatFormatting.GREEN + (here
                ? "Requested " + name + " to teleport to you."
                : "Requested to teleport to " + name + ".");
        String toTarget = ChatFormatting.AQUA + player.getGameProfile().getName() + (here
                ? " has invited you to teleport to them. Use /tpaccept."
                : " wants to teleport to you. Use /tpaccept.");
        Tpa.notify(player, toRequester, tick);
        Tpa.notify(target, toTarget, tick);
        return 1;
    }

    private static int accept(CommandContext<CommandSourceStack> context, String who) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        if (!(source.getEntity() instanceof ServerPlayer)) {
            source.sendFailure(Component.literal(ChatFormatting.RED + "Only players can use this command."));
            return 0;
        }
        ServerPlayer player = (ServerPlayer) source.getEntity();
        if (server.getPlayerList().getPlayers().size() <= 1) {
            return fail(player, server, "No other players online.");
        }
        List<TpaRequest> selected = select(Tpa.registry().inbound(player.getUUID()), who, server, true);
        if (selected.isEmpty()) return fail(player, server, "You have no pending teleport request to accept.");

        int tick = server.getTickCount();
        int done = 0;
        String lastCounterpart = "";
        for (TpaRequest request : selected) {
            if (Tpa.registry().remove(request.id) == null) continue;
            ServerPlayer requester = server.getPlayerList().getPlayer(request.requester);
            String requesterName = nameOf(server, request.requester);
            lastCounterpart = requesterName;
            if (request.isHere()) {
                Tpa.teleport(player, request.snapshot, server);
                Tpa.notify(requester, ChatFormatting.GREEN + player.getGameProfile().getName()
                        + " accepted your teleport request.", tick);
            } else {
                ServerPlayer target = server.getPlayerList().getPlayer(request.target);
                if (target == null) {
                    Tpa.notify(player, ChatFormatting.RED + requesterName + " is no longer online.", tick);
                    done++;
                    continue;
                }
                Tpa.teleport(requester, Tpa.poseOf(target), server);
                Tpa.notify(requester, ChatFormatting.GREEN + "Teleported to " + target.getGameProfile().getName() + ".", tick);
            }
            done++;
        }
        if (done > 0) {
            Tpa.notify(player, ChatFormatting.GREEN + (done == 1
                    ? "Accepted " + lastCounterpart + "'s teleport request."
                    : "Accepted " + done + " teleport requests."), tick);
        }
        return done > 0 ? 1 : 0;
    }

    private static int deny(CommandContext<CommandSourceStack> context, String who) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        if (!(source.getEntity() instanceof ServerPlayer)) {
            source.sendFailure(Component.literal(ChatFormatting.RED + "Only players can use this command."));
            return 0;
        }
        ServerPlayer player = (ServerPlayer) source.getEntity();
        if (server.getPlayerList().getPlayers().size() <= 1) {
            return fail(player, server, "No other players online.");
        }
        List<TpaRequest> selected = select(Tpa.registry().inbound(player.getUUID()), who, server, true);
        if (selected.isEmpty()) return fail(player, server, "You have no pending teleport request to deny.");

        int tick = server.getTickCount();
        int done = 0;
        String lastCounterpart = "";
        for (TpaRequest request : selected) {
            if (Tpa.registry().remove(request.id) == null) continue;
            String requesterName = nameOf(server, request.requester);
            lastCounterpart = requesterName;
            Tpa.notify(server.getPlayerList().getPlayer(request.requester),
                    ChatFormatting.RED + player.getGameProfile().getName() + " denied your teleport request.", tick);
            done++;
        }
        if (done > 0) {
            Tpa.notify(player, ChatFormatting.GREEN + (done == 1
                    ? "Denied " + lastCounterpart + "'s teleport request."
                    : "Denied " + done + " teleport requests."), tick);
        }
        return done > 0 ? 1 : 0;
    }

    private static int cancel(CommandContext<CommandSourceStack> context, String who) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        if (!(source.getEntity() instanceof ServerPlayer)) {
            source.sendFailure(Component.literal(ChatFormatting.RED + "Only players can use this command."));
            return 0;
        }
        ServerPlayer player = (ServerPlayer) source.getEntity();
        if (server.getPlayerList().getPlayers().size() <= 1) {
            return fail(player, server, "No other players online.");
        }
        List<TpaRequest> selected = select(Tpa.registry().outgoing(player.getUUID()), who, server, false);
        if (selected.isEmpty()) return fail(player, server, "You have no outgoing teleport request to cancel.");

        int tick = server.getTickCount();
        int done = 0;
        String lastCounterpart = "";
        for (TpaRequest request : selected) {
            if (Tpa.registry().remove(request.id) == null) continue;
            lastCounterpart = request.targetName;
            Tpa.notify(server.getPlayerList().getPlayer(request.target),
                    ChatFormatting.RED + player.getGameProfile().getName() + " cancelled their teleport request.", tick);
            done++;
        }
        if (done > 0) {
            Tpa.notify(player, ChatFormatting.GREEN + (done == 1
                    ? "Cancelled your request to " + lastCounterpart + "."
                    : "Cancelled " + done + " teleport requests."), tick);
        }
        return done > 0 ? 1 : 0;
    }

    /** Bare form picks the oldest; all/* picks every match; a name picks that counterpart. */
    private static List<TpaRequest> select(List<TpaRequest> source, String who, MinecraftServer server,
                                           boolean incoming) {
        List<TpaRequest> out = new ArrayList<>();
        if (source == null || source.isEmpty()) return out;
        if (who == null || who.isEmpty()) {
            out.add(source.get(0));
            return out;
        }
        if ("all".equalsIgnoreCase(who) || "*".equals(who)) {
            out.addAll(source);
            return out;
        }
        ServerPlayer other = Tpa.resolveTarget(server, who);
        for (TpaRequest request : source) {
            UUID counterpart = incoming ? request.requester : request.target;
            String name = incoming ? nameOf(server, request.requester) : request.targetName;
            if ((other != null && counterpart.equals(other.getUUID())) || who.equalsIgnoreCase(name)) {
                out.add(request);
                break;
            }
        }
        return out;
    }

    private static String nameOf(MinecraftServer server, UUID uuid) {
        ServerPlayer player = server.getPlayerList().getPlayer(uuid);
        return player == null ? "a player" : player.getGameProfile().getName();
    }

    /** Exactly one error line to the executing player; never a repeated line. */
    private static int fail(ServerPlayer player, MinecraftServer server, String text) {
        Tpa.notify(player, ChatFormatting.RED + text, server.getTickCount());
        return 0;
    }
}
