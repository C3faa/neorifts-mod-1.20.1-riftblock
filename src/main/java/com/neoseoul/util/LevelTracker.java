package com.neoseoul.util;

import net.minecraft.network.packet.s2c.play.OverlayMessageS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Показывает HUD-сообщение "Мобы стали сильнее" на каждом 10-м уровне игрока.
 * Совместимо с 1.20.1 (Yarn).
 */
public class LevelTracker {
    private static final Map<MinecraftServer, LevelTracker> INST = new WeakHashMap<>();
    public static LevelTracker get(MinecraftServer server) {
        return INST.computeIfAbsent(server, s -> new LevelTracker());
    }

    private final Map<UUID, Integer> lastLevel = new HashMap<>();

    public void tick(MinecraftServer server) {
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            int cur = p.experienceLevel;
            Integer prev = lastLevel.put(p.getUuid(), cur);
            if (prev == null) continue;

            if (cur != prev && cur > 0 && cur % 10 == 0) {
                p.networkHandler.sendPacket(new OverlayMessageS2CPacket(
                    Text.literal("Мобы стали сильнее").formatted(Formatting.RED, Formatting.BOLD)
                ));
            }
        }
    }
}
