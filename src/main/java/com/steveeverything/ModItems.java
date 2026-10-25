package com.steveeverything;

import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(SteveEverything.MOD_ID);

    // same numbers as raw / cooked beef
    public static final DeferredItem<Item> RAW_STEVE = ITEMS.registerSimpleItem("raw_steve",
            new Item.Properties().food(new FoodProperties.Builder().nutrition(3).saturationModifier(0.3f).build()));

    public static final DeferredItem<Item> COOKED_STEVE = ITEMS.registerSimpleItem("cooked_steve",
            new Item.Properties().food(new FoodProperties.Builder().nutrition(8).saturationModifier(0.8f).build()));

    private ModItems() {}

    public static void addToCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FOOD_AND_DRINKS) {
            event.accept(RAW_STEVE);
            event.accept(COOKED_STEVE);
        }
    }
}
