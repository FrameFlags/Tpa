package com.frameflags.tpa_test;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;

/** Test-only server mod that drives the in-game TPA probe. */
@Mod(modid = TpaProbeMod.MODID, name = TpaProbeMod.NAME, version = TpaProbeMod.VERSION,
        acceptableRemoteVersions = "*")
public final class TpaProbeMod {
    public static final String MODID = "tpa_test";
    public static final String NAME = "Tpa in-game probe (test only)";
    public static final String VERSION = "1.0.0";

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(new TpaProbe());
        TpaProbe.bootstrap(event.getModConfigurationDirectory().getParentFile().toPath());
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        MinecraftServer server = event.getServer();
        TpaProbe.serverStarting(server);
    }
}
