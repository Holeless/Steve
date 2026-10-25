package com.steveeverything;

import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;

/** Any mob that would drop raw meat drops Raw Steve instead; burning mobs (cooked meat) drop Cooked Steve. */
public final class MeatDrops {
    private MeatDrops() {}

    private static boolean isRawMeat(Item i) {
        return i == Items.BEEF || i == Items.PORKCHOP || i == Items.MUTTON || i == Items.CHICKEN || i == Items.RABBIT;
    }

    private static boolean isCookedMeat(Item i) {
        return i == Items.COOKED_BEEF || i == Items.COOKED_PORKCHOP || i == Items.COOKED_MUTTON
                || i == Items.COOKED_CHICKEN || i == Items.COOKED_RABBIT;
    }

    @SubscribeEvent
    public static void onDrops(LivingDropsEvent event) {
        if (event.getEntity() instanceof Player) return;
        for (ItemEntity drop : event.getDrops()) {
            ItemStack stack = drop.getItem();
            Item item = stack.getItem();
            if (isRawMeat(item)) {
                drop.setItem(new ItemStack(ModItems.RAW_STEVE.get(), stack.getCount()));
            } else if (isCookedMeat(item)) {
                drop.setItem(new ItemStack(ModItems.COOKED_STEVE.get(), stack.getCount()));
            }
        }
    }
}
