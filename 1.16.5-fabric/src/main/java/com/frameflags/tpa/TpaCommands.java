package com.frameflags.tpa;

import com.frameflags.tpa.core.TpaPose;
import com.frameflags.tpa.core.TpaRequest;
import com.frameflags.tpa.core.TpaResult;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.LiteralText;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.registry.Registry;
import net.minecraft.util.registry.RegistryKey;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Brigadier adapter for Fabric 1.16.5. No op / cheats / permission node. */
public final class TpaCommands {
    private TpaCommands() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher, boolean dedicated) {
        dispatcher.register(LiteralArgumentBuilder.<ServerCommandSource>literal("tpa")
                .executes(ctx -> usage(ctx, "tpa"))
                .then(RequiredArgumentBuilder.<ServerCommandSource, String>argument("name", StringArgumentType.word())
                        .executes(ctx -> request(ctx, false))));
        dispatcher.register(LiteralArgumentBuilder.<ServerCommandSource>literal("tpahere")
                .executes(ctx -> usage(ctx, "tpahere"))
                .then(RequiredArgumentBuilder.<ServerCommandSource, String>argument("name", StringArgumentType.word())
                        .executes(ctx -> request(ctx, true))));

        dispatcher.register(LiteralArgumentBuilder.<ServerCommandSource>literal("tpaccept")
                .executes(ctx -> accept(ctx, null, false))
                .then(RequiredArgumentBuilder.<ServerCommandSource, String>argument("name", StringArgumentType.word())
                        .executes(ctx -> accept(ctx, StringArgumentType.getString(ctx, "name"), false))));
        dispatcher.register(LiteralArgumentBuilder.<ServerCommandSource>literal("tpacceptall")
                .executes(ctx -> accept(ctx, null, true))
                .then(RequiredArgumentBuilder.<ServerCommandSource, String>argument("name", StringArgumentType.word())
                        .executes(ctx -> accept(ctx, StringArgumentType.getString(ctx, "name"), true))));

        registerRemoval(dispatcher, "tpdeny", false, false);
        registerRemoval(dispatcher, "tpdenyall", false, true);
        registerRemoval(dispatcher, "tpadeny", false, false);
        registerRemoval(dispatcher, "tpadenyall", false, true);
        registerRemoval(dispatcher, "tpacancel", true, false);
        registerRemoval(dispatcher, "tpacancelall", true, true);
    }

    private static void registerRemoval(CommandDispatcher<ServerCommandSource> dispatcher,
                                        String name, final boolean outgoing, final boolean all) {
        dispatcher.register(LiteralArgumentBuilder.<ServerCommandSource>literal(name)
                .executes(ctx -> remove(ctx, null, outgoing, all))
                .then(RequiredArgumentBuilder.<ServerCommandSource, String>argument("name", StringArgumentType.word())
                        .executes(ctx -> remove(ctx, StringArgumentType.getString(ctx, "name"), outgoing, all))));
    }

    /** Yarn 1.16.5 declares getPlayer() as throwing for a non-player source. */
    private static ServerPlayerEntity playerOf(ServerCommandSource source) {
        try {
            return source.getPlayer();
        } catch (CommandSyntaxException ignored) {
            return null;
        }
    }

    private static int usage(CommandContext<ServerCommandSource> ctx, String name) {
        chat(ctx.getSource(), "Usage: /" + name + " <player>");
        return 0;
    }

    private static void chat(ServerCommandSource source, String text) {
        source.sendFeedback(new LiteralText(text), false);
        Tpa.chat(playerOf(source) == null ? null : playerOf(source).getUuid(), text, (int) Tpa.tick());
    }

    static void notify(UUID uuid, MinecraftServer server, String text) {
        Tpa.chat(uuid, text, (int) Tpa.tick());
        ServerPlayerEntity player = Tpa.findPlayer(server, uuid);
        if (player != null) player.sendMessage(new LiteralText(text), false);
    }

    static ServerPlayerEntity resolveTarget(MinecraftServer server, String name) {
        return Tpa.resolveTarget(server, name);
    }

    static TpaPose pose(ServerPlayerEntity player) {
        return new TpaPose(player.getX(), player.getY(), player.getZ(),
                player.yaw, player.pitch, dimensionKey(player));
    }

    private static String dimensionKey(ServerPlayerEntity player) {
        try {
            return player.world.getRegistryKey().getValue().toString();
        } catch (RuntimeException ignored) {
            return "minecraft:overworld";
        }
    }

    /** Exact pose, zero velocity, cross-dimension when the key resolves. */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    static void teleport(ServerPlayerEntity player, TpaPose dest, MinecraftServer server) {
        if (player == null || dest == null) return;
        ServerWorld world = (ServerWorld) player.world;
        try {
            Identifier id = Identifier.tryParse(dest.dimension);
            if (id != null && server != null) {
                RegistryKey<World> key = (RegistryKey<World>) (RegistryKey) RegistryKey.of(Registry.DIMENSION_KEY, id);
                ServerWorld resolved = server.getWorld(key);
                if (resolved != null) world = resolved;
            }
        } catch (RuntimeException ignored) {
            // Missing dimension falls back to the player's current level.
        }
        player.teleport(world, dest.x, dest.y, dest.z, dest.yaw, dest.pitch);
        player.setVelocity(Vec3d.ZERO);
    }

    private static int request(CommandContext<ServerCommandSource> ctx, boolean here) {
        ServerCommandSource source = ctx.getSource();
        ServerPlayerEntity player = playerOf(source);
        if (player == null) {
            chat(source, "Only players can use this command.");
            return 0;
        }
        MinecraftServer server = source.getMinecraftServer();
        String name = StringArgumentType.getString(ctx, "name");
        ServerPlayerEntity target = resolveTarget(server, name);
        if (target == null) {
            chat(source, "No such player '" + name + "'.");
            return 0;
        }
        if (target.getUuid().equals(player.getUuid())) {
            chat(source, "You cannot send a teleport request to yourself.");
            return 0;
        }
        TpaPose snapshot = here ? pose(player) : null;
        long deadline = Tpa.tick() + (long) Tpa.config().expirySeconds * 20L;
        TpaResult result = Tpa.registry().request(player.getUuid(), target.getUuid(),
                target.getName().getString(), here ? TpaRequest.Kind.TO_REQUESTER : TpaRequest.Kind.TO_TARGET,
                snapshot, deadline, Tpa.config().maxOutgoing);
        switch (result) {
            case OK:
                chat(source, here
                        ? "Asked " + target.getName().getString() + " to teleport to you."
                        : "Requested to teleport to " + target.getName().getString() + ".");
                notify(target.getUuid(), server, player.getName().getString()
                        + (here ? " wants you to teleport to them." : " wants to teleport to you.")
                        + " Use /tpaccept or /tpdeny.");
                return 1;
            case SELF_REQUEST:
                chat(source, "You cannot send a teleport request to yourself.");
                return 0;
            case DUPLICATE:
                chat(source, "A pending request with " + target.getName().getString() + " already exists.");
                return 0;
            case OUTGOING_LIMIT:
                chat(source, "Too many outgoing requests (max " + Tpa.config().maxOutgoing + ").");
                return 0;
            default:
                chat(source, "Could not create the request.");
                return 0;
        }
    }

    private static List<TpaRequest> select(CommandContext<ServerCommandSource> ctx, ServerPlayerEntity player,
                                           String arg, boolean all, boolean inbound) {
        MinecraftServer server = ctx.getSource().getMinecraftServer();
        List<TpaRequest> allRequests = inbound
                ? Tpa.registry().inbound(player.getUuid())
                : Tpa.registry().outgoing(player.getUuid());
        List<TpaRequest> chosen = new ArrayList<TpaRequest>();
        if (all) {
            chosen.addAll(allRequests);
        } else if (arg != null && !arg.trim().isEmpty() && !"all".equalsIgnoreCase(arg) && !"*".equals(arg)) {
            ServerPlayerEntity other = resolveTarget(server, arg);
            if (other == null) {
                chat(ctx.getSource(), "No such player '" + arg + "'.");
                return null;
            }
            for (TpaRequest request : allRequests) {
                UUID otherId = inbound ? request.requester : request.target;
                if (otherId.equals(other.getUuid())) {
                    chosen.add(request);
                    break;
                }
            }
            if (chosen.isEmpty()) {
                chat(ctx.getSource(), "No pending request with " + arg + ".");
                return null;
            }
        } else if (!allRequests.isEmpty()) {
            chosen.add(allRequests.get(0));
        }
        return chosen;
    }

    private static int accept(CommandContext<ServerCommandSource> ctx, String arg, boolean forcedAll) {
        ServerCommandSource source = ctx.getSource();
        ServerPlayerEntity player = playerOf(source);
        if (player == null) {
            chat(source, "Only players can use this command.");
            return 0;
        }
        boolean all = forcedAll || "all".equalsIgnoreCase(arg) || "*".equals(arg);
        List<TpaRequest> chosen = select(ctx, player, arg, all, true);
        if (chosen == null) return 0;
        if (chosen.isEmpty()) {
            chat(source, "No pending teleport requests.");
            return 0;
        }
        MinecraftServer server = source.getMinecraftServer();
        int accepted = 0;
        for (TpaRequest request : chosen) {
            if (request.isHere()) {
                if (request.snapshot == null) {
                    chat(source, "That request has no saved position.");
                    continue;
                }
                teleport(player, request.snapshot, server);
            } else {
                ServerPlayerEntity mover = Tpa.findPlayer(server, request.requester);
                if (mover == null) {
                    chat(source, "The requester is offline; request cancelled.");
                    Tpa.registry().remove(request.id);
                    continue;
                }
                teleport(mover, pose(player), server);
            }
            Tpa.registry().remove(request.id);
            accepted++;
        }
        if (accepted > 0) chat(source, "Accepted " + accepted + " teleport request(s).");
        return accepted;
    }

    private static int remove(CommandContext<ServerCommandSource> ctx, String arg,
                              boolean outgoing, boolean forcedAll) {
        ServerCommandSource source = ctx.getSource();
        ServerPlayerEntity player = playerOf(source);
        if (player == null) {
            chat(source, "Only players can use this command.");
            return 0;
        }
        boolean all = forcedAll || "all".equalsIgnoreCase(arg) || "*".equals(arg);
        List<TpaRequest> chosen = select(ctx, player, arg, all, !outgoing);
        if (chosen == null) return 0;
        if (chosen.isEmpty()) {
            chat(source, outgoing ? "No outgoing teleport requests." : "No pending teleport requests.");
            return 0;
        }
        int count = 0;
        for (TpaRequest request : chosen) {
            Tpa.registry().remove(request.id);
            count++;
        }
        chat(source, (outgoing ? "Cancelled " : "Denied ") + count + " teleport request(s).");
        return count;
    }
}
