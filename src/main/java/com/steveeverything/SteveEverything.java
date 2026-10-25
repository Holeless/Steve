package com.steveeverything;

import com.steveeverything.client.SteveClient;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;

/** Client-only mod: turns every mob into Steve. Does nothing on a dedicated server. */
@Mod(value = SteveEverything.MOD_ID, dist = Dist.CLIENT)
public class SteveEverything {
    public static final String MOD_ID = "steveeverything";

    public SteveEverything(IEventBus modBus) {
        modBus.addListener(SteveClient::onRegisterReloadListeners);
        NeoForge.EVENT_BUS.register(SteveClient.class);
    }
}
