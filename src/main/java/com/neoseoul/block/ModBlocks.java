package com.neoseoul.block;

import com.neoseoul.NeoriftsMod;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.block.Block;
import net.minecraft.block.AbstractBlock;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public final class ModBlocks {
    public static final RunicObsidianBlock RUNIC_OBSIDIAN = new RunicObsidianBlock(
            AbstractBlock.Settings.create().strength(50.0f, 1200.0f).requiresTool()
    );

    public static void register() {
        // Блок
        Registry.register(Registries.BLOCK,
                new Identifier(NeoriftsMod.MOD_ID, "runic_obsidian"),
                RUNIC_OBSIDIAN);

        // Айтем блока
        Registry.register(Registries.ITEM,
                new Identifier(NeoriftsMod.MOD_ID, "runic_obsidian"),
                new BlockItem(RUNIC_OBSIDIAN, new Item.Settings()));

        // Вкладка креатива
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.FUNCTIONAL).register(entries -> {
            entries.add(RUNIC_OBSIDIAN.asItem());
        });
    }

    private ModBlocks() {}
}
