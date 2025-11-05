package com.neoseoul;

import com.mojang.brigadier.CommandDispatcher;
import com.neoseoul.rift.RiftManager;
import com.neoseoul.util.LevelWatcher;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;

import static net.minecraft.server.command.CommandManager.literal;

public class NeoriftsMod implements ModInitializer {
    public static final String MODID = "neorifts";

    @Override
    public void onInitialize() {
        // Тики сервера: обновляем рифт и отслеживаем уровни игроков
        ServerTickEvents.END_SERVER_TICK.register((MinecraftServer server) -> {
            RiftManager.get(server).tick(server);
            LevelWatcher.get(server).tick(server);
        });

        // Команды
        CommandRegistrationCallback.EVENT.register((CommandDispatcher<ServerCommandSource> disp, reg, env) -> {
            disp.register(literal("rift").requires(src -> src.hasPermissionLevel(2))
                .then(literal("create").executes(ctx -> {
                    RiftManager.get(ctx.getSource().getServer())
                            .createNear(ctx.getSource().getPlayer());
                    return 1;
                }))
                .then(literal("force").executes(ctx -> {
                    RiftManager.get(ctx.getSource().getServer())
                            .forceNear(ctx.getSource().getPlayer());
                    return 1;
                }))
                .then(literal("despawn").executes(ctx -> {
                    RiftManager.get(ctx.getSource().getServer())
                            .despawn(true);
                    return 1;
                })));
        });
    }
}
