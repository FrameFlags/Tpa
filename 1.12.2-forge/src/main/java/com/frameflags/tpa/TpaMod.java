package com.frameflags.tpa;

import com.frameflags.tpa.core.TpaRequest;
import com.frameflags.tpa.TpaCommands.Mode;
import java.util.List;
import java.util.UUID;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/** Server-side entry point: startup init, commands, tick loop and lifecycle purge. */
@Mod(modid = TpaMod.MODID, name = TpaMod.NAME, version = TpaMod.VERSION,
        acceptableRemoteVersions = "*")
public final class TpaMod {
    public static final String MODID = "tpa";
    public static final String NAME = "Tpa";
    public static final String VERSION = "1.0.0";

    private MinecraftServer server;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        Tpa.init(event.getModConfigurationDirectory().getParentFile().toPath());
        MinecraftForge.EVENT_BUS.register(this);
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        server = event.getServer();
        Tpa.init(server.getDataDirectory().toPath());
        Tpa.clear();
        event.registerServerCommand(TpaCommands.command("tpa", Mode.TPA));
        event.registerServerCommand(TpaCommands.command("tpahere", Mode.TPAHERE));
        event.registerServerCommand(TpaCommands.command("tpaccept", Mode.ACCEPT));
        event.registerServerCommand(TpaCommands.command("tpacceptall", Mode.ACCEPT, true));
        event.registerServerCommand(TpaCommands.command("tpdeny", Mode.DENY));
        event.registerServerCommand(TpaCommands.command("tpdenyall", Mode.DENY, true));
        event.registerServerCommand(TpaCommands.command("tpadeny", Mode.DENY));
        event.registerServerCommand(TpaCommands.command("tpadenyall", Mode.DENY, true));
        event.registerServerCommand(TpaCommands.command("tpacancel", Mode.CANCEL));
        event.registerServerCommand(TpaCommands.command("tpacancelall", Mode.CANCEL, true));
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || server == null) return;
        for (TpaRequest due : Tpa.advanceTick()) {
            String who = due.targetName == null || due.targetName.isEmpty() ? "a player" : due.targetName;
            TpaCommands.notify(due.requester, server,
                    "Your teleport request to " + who + " expired.");
            TpaCommands.notify(due.target, server,
                    "The teleport request from " + who + " expired.");
        }
    }

    @SubscribeEvent
    public void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof EntityPlayerMP) purge(event.getEntity().getUniqueID());
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.player instanceof EntityPlayerMP) purge(event.player.getUniqueID());
    }

    private static void purge(UUID player) {
        if (player != null) Tpa.purge(player);
    }
}
