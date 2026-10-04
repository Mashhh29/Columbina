package com.columbina;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.item.v1.FabricItemSettings;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public class ColumbinaMod implements ModInitializer {
    public static final String MODID = "columbina";

    public static final Item BACULO = Registry.register(
            Registries.ITEM,
            new Identifier(MODID, "baculo_columbina"),
            new ColumbinaStaffItem(new FabricItemSettings().maxCount(1)));

    @Override
    public void onInitialize() {
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> entries.add(BACULO));
        ServerTickEvents.END_SERVER_TICK.register(ColumbinaAbilities::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> ColumbinaAbilities.clear());
    }
}
