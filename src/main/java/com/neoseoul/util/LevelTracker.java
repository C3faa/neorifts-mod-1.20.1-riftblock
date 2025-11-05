package com.neoseoul.util;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.Map;
import java.util.WeakHashMap;

public class LevelTracker {
    private static final Map<MinecraftServer, LevelTracker> INST = new WeakHashMap<>();
    public static void init() {}
    public static LevelTracker get(MinecraftServer server) { return INST.computeIfAbsent(server, s -> new LevelTracker()); }

    private final Map<ServerPlayerEntity, Integer> lastLevel = new WeakHashMap<>();

    public void tick(MinecraftServer server) {
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            int prev = lastLevel.getOrDefault(p, p.experienceLevel);
            int cur = p.experienceLevel;
            if (cur > prev) {
                // if crossed a multiple of 10
                if ((cur / 10) > (prev / 10)) {
                    p.sendMessage(Text.literal("§dМобы стали сильнее"), true);
                }
            }
            lastLevel.put(p, cur);
        }
    }
}
