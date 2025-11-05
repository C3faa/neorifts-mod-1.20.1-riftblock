package com.neoseoul.block;

import com.neoseoul.NeoriftsMod;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public final class ModBlocks {

    // Сам блок рифта
    public static final Block RUNIC_OBSIDIAN =
            new RunicObsidianBlock(AbstractBlock.Settings.copy(Blocks.OBSIDIAN).nonOpaque());

    private ModBlocks() {}

    public static void register() {
        // Регистрируем блок
        Registry.register(Registries.BLOCK,
                new Identifier(NeoriftsMod.MOD_ID, "runic_obsidian"),
                RUNIC_OBSIDIAN);

        // Регистрируем предмет-блок
        Registry.register(Registries.ITEM,
                new Identifier(NeoriftsMod.MOD_ID, "runic_obsidian"),
                new BlockItem(RUNIC_OBSIDIAN, new Item.Settings()));
    }
}
