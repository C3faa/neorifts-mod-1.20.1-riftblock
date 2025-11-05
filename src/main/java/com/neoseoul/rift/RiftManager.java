package com.neoseoul.rift;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;

import java.util.Objects;
import java.util.UUID;

public final class RiftManager {

    // ---- singleton на сервер ----
    private static RiftManager instance;

    public static RiftManager get(MinecraftServer server) {
        if (instance == null) instance = new RiftManager();
        return instance;
    }

    // ---- внутреннее состояние (минимально для компиляции) ----
    private BlockPos anchor;                // центр текущего разлома
    private UUID   activatorUuid;           // кто активировал
    private int    ticksLeft;               // оставшееся время
    private boolean active;

    private final Random rnd = Random.create(); // ВАЖНО: net.minecraft.util.math.random.Random (совместим с MathHelper)

    // ---- публичный API, который вызывается из других классов ----

    /** тикаем менеджер каждый серверный тик */
    public void tick(MinecraftServer server) {
        if (!active) return;
        if (ticksLeft > 0) {
            ticksLeft--;

            // простенький спаун моба раз в N тиков возле якоря (демо)
            if (ticksLeft % 100 == 0 && anchor != null) {
                var world = anchorWorld(server);
                if (world != null) {
                    spawnMobAround(world, anchor);
                }
            }

            // раз в 40 тиков обновляем босс-бар/худ (если у тебя есть свой HUD — дерни его тут)
            if (ticksLeft % 40 == 0) {
                broadcast(server, Text.literal("Разлом: осталось " + ticksLeft / 20 + "s").formatted(Formatting.DARK_AQUA));
            }
        } else {
            despawn(server, false);
        }
    }

    /** создать разлом рядом с игроком */
    public void createNear(ServerPlayerEntity player) {
        var world = player.getServerWorld();
        // радиус 24–48 блоков от игрока
        int dist = MathHelper.nextInt(rnd, 24, 48);
        int angle = MathHelper.nextInt(rnd, 0, 359);
        double rad = Math.toRadians(angle);
        int dx = (int) Math.round(Math.cos(rad) * dist);
        int dz = (int) Math.round(Math.sin(rad) * dist);

        BlockPos pos = player.getBlockPos().add(dx, 0, dz);
        pos = findSurface(world, pos);

        startRift(world, pos, player.getUuid());
    }

    /** принудительно «рядом» — без случайного радиуса */
    public void forceNear(ServerPlayerEntity player) {
        var world = player.getServerWorld();
        BlockPos pos = findSurface(world, player.getBlockPos().add(3, 0, 0));
        startRift(world, pos, player.getUuid());
    }

    /** закрыть текущий разлом */
    public void despawn(boolean silent) {
        // нужен server для бродкаста – поэтому делаем no-op, если нет активного мира
        this.active = false;
        this.anchor = null;
        this.ticksLeft = 0;
        this.activatorUuid = null;
    }

    /** перегрузка для вызовов с сервера */
    public void despawn(MinecraftServer server, boolean silent) {
        this.active = false;
        this.ticksLeft = 0;
        this.activatorUuid = null;

        if (!silent) {
            var w = anchorWorld(server);
            if (w != null) {
                broadcast(server, Text.literal("Разлом исчез").formatted(Formatting.GRAY));
            }
        }
        this.anchor = null;
    }

    /** клик по руническому обсидиану */
    public void onBlockActivated(ServerWorld world, BlockPos pos, PlayerEntity player) {
        startRift(world, pos, player.getUuid());
    }

    // ---- внутренняя реализация ----

    private void startRift(ServerWorld world, BlockPos pos, UUID activator) {
        this.anchor = pos.toImmutable();
        this.activatorUuid = activator;
        this.active = true;
        this.ticksLeft = 20 * 60; // 60 секунд демо-таймер

        broadcast(world.getServer(),
                Text.literal("Открылся разлом у X=" + pos.getX() + " Y=" + pos.getY() + " Z=" + pos.getZ())
                        .formatted(Formatting.LIGHT_PURPLE));

        // спаун первого моба
        spawnMobAround(world, pos);
    }

    private void spawnMobAround(ServerWorld world, BlockPos center) {
        // небольшое смещение
        int dx = MathHelper.nextInt(rnd, -6, 6);
        int dz = MathHelper.nextInt(rnd, -6, 6);
        BlockPos at = findSurface(world, center.add(dx, 0, dz));

        // простой моб — зомби (для примера); заменишь на свой EntityType при необходимости
        EntityType<?> type = EntityType.ZOMBIE;

        // В 1.20.1 удобная сигнатура spawn(world, pos, reason)
        Entity e = type.spawn(world, at, SpawnReason.EVENT);
        if (e instanceof MobEntity mob) {
            // на всякий — немного внимания к игроку-активатору
            if (activatorUuid != null) {
                var nearest = world.getClosestPlayer(at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5, 32, false);
                if (nearest != null) {
                    mob.setTarget(nearest);
                }
            }
        }
    }

    private BlockPos findSurface(ServerWorld world, BlockPos pos) {
        // поднимаемся/опускаемся к поверхности
        BlockPos.Mutable m = pos.mutableCopy();
        int y = m.getY();
        y = MathHelper.clamp(y, world.getBottomY() + 1, world.getTopY() - 2);
        m.setY(y);

        // поднимемся до воздуха
        while (!world.isAir(m) && m.getY() < world.getTopY() - 2) {
            m.setY(m.getY() + 1);
        }
        // опустимся на блок «земли»
        while (world.isAir(m) && m.getY() > world.getBottomY() + 1) {
            m.setY(m.getY() - 1);
        }
        return m.up().toImmutable();
        }

    private ServerWorld anchorWorld(MinecraftServer server) {
        if (anchor == null) return null;
        // по умолчанию — основной мир
        return server.getOverworld();
    }

    private void broadcast(MinecraftServer server, Text message) {
        server.getPlayerManager().broadcast(message, false);
    }
}
