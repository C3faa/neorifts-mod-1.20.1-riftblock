package com.neoseoul;

import com.neoseoul.rift.RiftManager;
import com.neoseoul.util.LevelTracker;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;

import static net.minecraft.server.command.CommandManager.literal;

public class NeoriftsMod implements ModInitializer {
    public static final String MODID = "neorifts";

    @Override
    public void onInitialize() {
        // Тик каждого сервера: менеджер разломов + трекер уровней
        ServerTickEvents.END_SERVER_TICK.register((MinecraftServer server) -> {
            RiftManager.get(server).tick(server);
            LevelTracker.get(server).tick(server);
        });

        // Регистрация команд /rift
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(literal("rift").requires(src -> src.hasPermissionLevel(2))
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
                }))
            );
        });
    }
}
