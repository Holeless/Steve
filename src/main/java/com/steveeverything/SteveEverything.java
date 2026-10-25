package com.steveeverything;

import com.steveeverything.client.ClientInit;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Turns every mob into Steve (client side) and makes meat drops into Raw Steve (needs the mod on the server too).
 */
@Mod(SteveEverything.MOD_ID)
public class SteveEverything {
    public static final String MOD_ID = "steveeverything";

    public SteveEverything(IEventBus modBus) {
        ModItems.ITEMS.register(modBus);
        modBus.addListener(ModItems::addToCreativeTab);
        NeoForge.EVENT_BUS.register(MeatDrops.class);

        // everything that touches rendering lives behind this check so a dedicated server never loads it
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ClientInit.init(modBus);
        }
    }
}
