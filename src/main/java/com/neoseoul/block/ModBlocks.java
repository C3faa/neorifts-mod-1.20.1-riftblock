package com.neoseoul.block;

import com.neoseoul.NeoriftsMod;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.MapColor;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.util.Identifier;

public class ModBlocks {
    public static Block RUNIC_OBSIDIAN;

    public static void register() {
        RUNIC_OBSIDIAN = Registry.register(Registries.BLOCK,
                new Identifier(NeoriftsMod.MODID, "runic_obsidian"),
                new RunicObsidianBlock(AbstractBlock.Settings.create()
                        .mapColor(MapColor.BLACK)
                        .strength(50.0F, 1200.0F)
                        .requiresTool()
                        .sounds(BlockSoundGroup.DEEPSLATE)));

        Registry.register(Registries.ITEM,
                new Identifier(NeoriftsMod.MODID, "runic_obsidian"),
                new BlockItem(RUNIC_OBSIDIAN, new Item.Settings()));

        ItemGroupEvents.modifyEntriesEvent(ItemGroups.BUILDING_BLOCKS).register(entries -> {
            entries.add(RUNIC_OBSIDIAN.asItem());
        });
    }
}
