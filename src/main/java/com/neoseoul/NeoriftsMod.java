package com.neoseoul;

import com.neoseoul.rift.RiftManager;
import com.neoseoul.util.LevelTracker;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import static net.minecraft.server.command.CommandManager.literal;

public class NeoriftsMod implements ModInitializer {
    public static final String MOD_ID = "neorifts";

    @Override
    public void onInitialize() {
        // Тик сервера: менеджер разломов + трекер уровней
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            RiftManager.get(server).tick(server);
            LevelTracker.get(server).tick(server);
        });

        // Команды /rift
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, env) -> {
            dispatcher.register(literal("rift").requires(src -> src.hasPermissionLevel(2))
                .then(literal("create").executes(ctx -> {
                    var p = ctx.getSource().getPlayer();
                    if (p != null) {
                        RiftManager.get(ctx.getSource().getServer()).createNear(p);
                    }
                    return 1;
                }))
                .then(literal("force").executes(ctx -> {
                    var p = ctx.getSource().getPlayer();
                    if (p != null) {
                        RiftManager.get(ctx.getSource().getServer()).forceNear(p);
                    }
                    return 1;
                }))
                .then(literal("despawn").executes(ctx -> {
                    RiftManager.get(ctx.getSource().getServer()).despawn(true);
                    return 1;
                }))
            );
        });
    }
}
