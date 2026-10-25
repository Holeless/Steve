package com.steveeverything.client;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;

/** Client-only wiring. Only ever called when running on the physical client. */
public final class ClientInit {
    private ClientInit() {}

    public static void init(IEventBus modBus) {
        modBus.addListener(SteveClient::onRegisterReloadListeners);
        NeoForge.EVENT_BUS.register(SteveClient.class);
    }
}
