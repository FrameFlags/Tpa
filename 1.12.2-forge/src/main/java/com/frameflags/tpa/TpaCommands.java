package com.frameflags.tpa;

import com.frameflags.tpa.core.TpaPose;
import com.frameflags.tpa.core.TpaRequest;
import com.frameflags.tpa.core.TpaResult;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** All TPA commands for 1.12.2. No op / cheats / permission node required. */
public final class TpaCommands {
    private TpaCommands() {
    }

    enum Mode { TPA, TPAHERE, ACCEPT, DENY, CANCEL }

    public static TpaCommand command(String name, Mode mode) {
        return new TpaCommand(name, mode, false);
    }

    public static TpaCommand command(String name, Mode mode, boolean all) {
        return new TpaCommand(name, mode, all);
    }

    /** One message per outcome, recorded in the per-player probe log. */
    public static void chat(ICommandSender sender, String text) {
        if (sender != null) sender.sendMessage(new TextComponentString(text));
        UUID uuid = sender instanceof EntityPlayerMP ? ((EntityPlayerMP) sender).getUniqueID() : null;
        Tpa.chat(uuid, text, (int) Tpa.tick());
    }

    static void notify(UUID uuid, MinecraftServer server, String text) {
        Tpa.chat(uuid, text, (int) Tpa.tick());
        if (server == null || uuid == null) return;
        EntityPlayerMP player = server.getPlayerList().getPlayerByUUID(uuid);
        if (player != null) player.sendMessage(new TextComponentString(text));
    }

    static EntityPlayerMP resolveTarget(MinecraftServer server, String name) {
        if (server == null || name == null || name.trim().isEmpty()) return null;
        for (EntityPlayerMP player : server.getPlayerList().getPlayers()) {
            if (player != null && name.equalsIgnoreCase(player.getName())) return player;
        }
        return null;
    }

    static TpaPose pose(EntityPlayerMP player) {
        return new TpaPose(player.posX, player.posY, player.posZ,
                player.rotationYaw, player.rotationPitch, String.valueOf(player.dimension));
    }

    /** Exact pose, zero momentum, cross-dimension via changeDimension when needed. */
    static void teleport(EntityPlayerMP player, TpaPose dest, MinecraftServer server) {
        if (player == null || dest == null) return;
        int dimension = player.dimension;
        try {
            dimension = Integer.parseInt(dest.dimension);
        } catch (RuntimeException ignored) {
            // Unparseable dimension key keeps the player in the current world.
        }
        EntityPlayerMP subject = player;
        if (dimension != player.dimension && server != null && server.getWorld(dimension) != null) {
            Entity moved = player.changeDimension(dimension);
            if (moved instanceof EntityPlayerMP) subject = (EntityPlayerMP) moved;
        }
        subject.setPositionAndRotation(dest.x, dest.y, dest.z, dest.yaw, dest.pitch);
        subject.motionX = 0.0D;
        subject.motionY = 0.0D;
        subject.motionZ = 0.0D;
    }

    static void execute(MinecraftServer server, EntityPlayerMP player, String[] args, TpaCommand command) {
        if (command.mode == Mode.TPA) request(server, player, args, false);
        else if (command.mode == Mode.TPAHERE) request(server, player, args, true);
        else if (command.mode == Mode.ACCEPT) accept(server, player, args, command.forcedAll);
        else if (command.mode == Mode.DENY) remove(server, player, args, false, command.forcedAll);
        else remove(server, player, args, true, command.forcedAll);
    }

    private static void request(MinecraftServer server, EntityPlayerMP player, String[] args, boolean here) {
        if (args.length != 1 || args[0].trim().isEmpty()) {
            chat(player, "Usage: /" + (here ? "tpahere" : "tpa") + " <player>");
            return;
        }
        EntityPlayerMP target = resolveTarget(server, args[0]);
        if (target == null) {
            chat(player, "No such player '" + args[0] + "'.");
            return;
        }
        if (target.getUniqueID().equals(player.getUniqueID())) {
            chat(player, "You cannot send a teleport request to yourself.");
            return;
        }
        TpaPose snapshot = here ? pose(player) : null;
        long deadline = Tpa.tick() + (long) Tpa.config().expirySeconds * 20L;
        TpaResult result = Tpa.registry().request(player.getUniqueID(), target.getUniqueID(),
                target.getName(), here ? TpaRequest.Kind.TO_REQUESTER : TpaRequest.Kind.TO_TARGET,
                snapshot, deadline, Tpa.config().maxOutgoing);
        switch (result) {
            case OK:
                chat(player, here
                        ? "Asked " + target.getName() + " to teleport to you."
                        : "Requested to teleport to " + target.getName() + ".");
                notify(target.getUniqueID(), server, player.getName()
                        + (here ? " wants you to teleport to them." : " wants to teleport to you.")
                        + " Use /tpaccept or /tpdeny.");
                break;
            case SELF_REQUEST:
                chat(player, "You cannot send a teleport request to yourself.");
                break;
            case DUPLICATE:
                chat(player, "A pending request with " + target.getName() + " already exists.");
                break;
            case OUTGOING_LIMIT:
                chat(player, "Too many outgoing requests (max " + Tpa.config().maxOutgoing + ").");
                break;
            default:
                chat(player, "Could not create the request.");
                break;
        }
    }

    private static List<TpaRequest> select(MinecraftServer server, EntityPlayerMP player,
                                           String arg, boolean all, boolean inbound) {
        List<TpaRequest> allRequests = inbound
                ? Tpa.registry().inbound(player.getUniqueID())
                : Tpa.registry().outgoing(player.getUniqueID());
        List<TpaRequest> chosen = new ArrayList<TpaRequest>();
        if (all) {
            chosen.addAll(allRequests);
        } else if (arg != null && !arg.trim().isEmpty() && !"all".equalsIgnoreCase(arg) && !"*".equals(arg)) {
            EntityPlayerMP other = resolveTarget(server, arg);
            if (other == null) {
                chat(player, "No such player '" + arg + "'.");
                return null;
            }
            for (TpaRequest request : allRequests) {
                UUID otherId = inbound ? request.requester : request.target;
                if (otherId.equals(other.getUniqueID())) {
                    chosen.add(request);
                    break;
                }
            }
            if (chosen.isEmpty()) {
                chat(player, "No pending request with " + arg + ".");
                return null;
            }
        } else if (!allRequests.isEmpty()) {
            chosen.add(allRequests.get(0));
        }
        return chosen;
    }

    private static void accept(MinecraftServer server, EntityPlayerMP player, String[] args, boolean forcedAll) {
        String arg = args.length > 0 ? args[0] : null;
        boolean all = forcedAll || "all".equalsIgnoreCase(arg) || "*".equals(arg);
        List<TpaRequest> chosen = select(server, player, arg, all, true);
        if (chosen == null) return;
        if (chosen.isEmpty()) {
            chat(player, "No pending teleport requests.");
            return;
        }
        int accepted = 0;
        for (TpaRequest request : chosen) {
            // Peek before consume: a failed resolution never loses the request.
            if (request.isHere()) {
                if (request.snapshot == null) {
                    chat(player, "That request has no saved position.");
                    continue;
                }
                teleport(player, request.snapshot, server);
            } else {
                EntityPlayerMP mover = server.getPlayerList().getPlayerByUUID(request.requester);
                if (mover == null) {
                    chat(player, "The requester is offline; request cancelled.");
                    Tpa.registry().remove(request.id);
                    continue;
                }
                // TO_TARGET resolves the accepter's LIVE pose at accept time.
                teleport(mover, pose(player), server);
            }
            Tpa.registry().remove(request.id);
            accepted++;
        }
        if (accepted > 0) chat(player, "Accepted " + accepted + " teleport request(s).");
    }

    private static void remove(MinecraftServer server, EntityPlayerMP player, String[] args,
                               boolean outgoing, boolean forcedAll) {
        String arg = args.length > 0 ? args[0] : null;
        boolean all = forcedAll || "all".equalsIgnoreCase(arg) || "*".equals(arg);
        List<TpaRequest> chosen = select(server, player, arg, all, !outgoing);
        if (chosen == null) return;
        if (chosen.isEmpty()) {
            chat(player, outgoing ? "No outgoing teleport requests." : "No pending teleport requests.");
            return;
        }
        int count = 0;
        for (TpaRequest request : chosen) {
            Tpa.registry().remove(request.id);
            count++;
        }
        chat(player, (outgoing ? "Cancelled " : "Denied ") + count + " teleport request(s).");
    }

    /** A single CommandBase handling all ten command spellings. */
    public static final class TpaCommand extends CommandBase {
        private final String name;
        final Mode mode;
        final boolean forcedAll;

        TpaCommand(String name, Mode mode, boolean forcedAll) {
            this.name = name;
            this.mode = mode;
            this.forcedAll = forcedAll;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getUsage(ICommandSender sender) {
            if (mode == Mode.TPA || mode == Mode.TPAHERE) return "/" + name + " <player>";
            return "/" + name + " [player|all|*]";
        }

        @Override
        public int getRequiredPermissionLevel() {
            return 0;
        }

        @Override
        public boolean checkPermission(MinecraftServer server, ICommandSender sender) {
            return true;
        }

        @Override
        public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
            if (!(sender instanceof EntityPlayerMP)) {
                chat(sender, "Only players can use this command.");
                return;
            }
            TpaCommands.execute(server, (EntityPlayerMP) sender, args, this);
        }

        @Override
        public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender,
                                              String[] args, BlockPos pos) {
            List<String> suggestions = new ArrayList<String>();
            if (args.length == 1 && server != null) {
                for (EntityPlayerMP candidate : server.getPlayerList().getPlayers()) {
                    if (candidate != null) suggestions.add(candidate.getName());
                }
                if (mode == Mode.ACCEPT || mode == Mode.DENY || mode == Mode.CANCEL) {
                    suggestions.add("all");
                    suggestions.add("*");
                }
            }
            return suggestions;
        }
    }
}
