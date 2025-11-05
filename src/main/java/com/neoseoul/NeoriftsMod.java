package com.neoseoul;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.neoseoul.block.ModBlocks;
import com.neoseoul.rift.RiftManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

public class NeoriftsMod implements ModInitializer {

    // Основной идентификатор мода
    public static final String MOD_ID = "neorifts";
    // Алиас, чтобы не падали места где используется MODID
    public static final String MODID = MOD_ID;

    @Override
    public void onInitialize() {
        // Регистрируем блоки/предметы
        ModBlocks.register();

        // Создаём инстанс RiftManager при старте сервера
        ServerLifecycleEvents.SERVER_STARTED.register((MinecraftServer server) -> {
            RiftManager.get(server);
        });

        // /rift create | /rift despawn | /rift force
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(
                    CommandManager.literal("rift")
                            .requires(src -> src.hasPermissionLevel(2))
                            .then(CommandManager.literal("create").executes(this::cmdCreate))
                            .then(CommandManager.literal("despawn").executes(this::cmdDespawn))
                            .then(CommandManager.literal("force").executes(this::cmdForce))
            );
        });
    }

    private int cmdCreate(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource src = ctx.getSource();
        ServerPlayerEntity p = src.getPlayer();
        if (p == null) {
            src.sendFeedback(() -> Text.literal("Нужен игрок."), false);
            return 0;
        }
        boolean ok = RiftManager.get(src.getServer()).createNear(p);
        src.sendFeedback(() -> Text.literal(ok ? "Рифт создан." : "Рифт уже существует или нет подходящей позиции."), false);
        return ok ? Command.SINGLE_SUCCESS : 0;
    }

    private int cmdDespawn(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource src = ctx.getSource();
        RiftManager.get(src.getServer()).despawn(false);
        src.sendFeedback(() -> Text.literal("Рифт удалён (если был)."), false);
        return Command.SINGLE_SUCCESS;
    }

    private int cmdForce(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource src = ctx.getSource();
        ServerPlayerEntity p = src.getPlayer();
        if (p == null) {
            src.sendFeedback(() -> Text.literal("Нужен игрок."), false);
            return 0;
        }
        boolean ok = RiftManager.get(src.getServer()).forceNear(p);
        src.sendFeedback(() -> Text.literal(ok ? "Рифт заспавнен принудительно." : "Не удалось заспавнить рифт."), false);
        return ok ? Command.SINGLE_SUCCESS : 0;
    }
}
