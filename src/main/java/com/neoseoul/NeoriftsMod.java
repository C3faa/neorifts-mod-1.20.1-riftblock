package com.neoseoul;

import com.neoseoul.block.ModBlocks;
import com.neoseoul.entity.DokkebiEntity;
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
        ModBlocks.register();
        DokkebiEntity.register();
        RiftManager.init();
        LevelTracker.init();

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            RiftManager.get(server).tick(server);
            LevelTracker.get(server).tick(server);
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, env) -> {
            dispatcher.register(literal("rift").then(literal("create").executes(ctx -> {
                MinecraftServer server = ctx.getSource().getServer();
                RiftManager.get(server).createNear(ctx.getSource().getPlayer());
                return 1;
            })).then(literal("despawn").executes(ctx -> {
                MinecraftServer server = ctx.getSource().getServer();
                RiftManager.get(server).despawn(server, true);
                return 1;
            })).then(literal("force").executes(ctx -> {
                MinecraftServer server = ctx.getSource().getServer();
                RiftManager.get(server).forceNear(ctx.getSource().getPlayer());
                return 1;
            })));
        });
    }
}
