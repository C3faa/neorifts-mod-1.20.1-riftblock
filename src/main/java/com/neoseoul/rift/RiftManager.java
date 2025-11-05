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
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class RiftManager {

    // ---- singleton ----
    private static RiftManager instance;
    public static RiftManager get(MinecraftServer server) {
        if (instance == null) instance = new RiftManager();
        return instance;
    }

    // ---- константы времени ----
    private static final int TPS = 20;
    private static final int MINUTE = 60 * TPS;

    // автоспавн: 4..5 минут
    private static final int AUTOSPAWN_MIN = 4 * MINUTE;
    private static final int AUTOSPAWN_MAX = 5 * MINUTE;

    // жизнь разлома: 5 минут
    private static final int RIFT_LIFETIME = 5 * MINUTE;

    // радиус смещения по XZ при createNear: 24..48 блоков
    private static final int NEAR_MIN_DIST = 24;
    private static final int NEAR_MAX_DIST = 48;

    // смещение по высоте относительно игрока: ±5
    private static final int PLAYER_Y_DELTA = 5;

    // поиск твёрдого пола по вертикали
    private static final int SURFACE_SEARCH_UP = 24;
    private static final int SURFACE_SEARCH_DOWN = 64;

    // ---- состояние ----
    private final Random rnd = Random.create();
    private boolean active = false;
    private BlockPos anchor = null;
    private UUID activatorUuid = null;
    private int ticksLeft = 0;

    // автоспавн по серверному времени (getOverworld().getTime())
    private long nextAutoSpawnAt = 0L;

    // флаги уведомлений
    private boolean sent3m = false;
    private boolean sent1m = false;
    private boolean sent30s = false;
    private boolean didFinalCountdown = false;

    // ---- публичные вызовы ----

    /** вызывать каждый тик сервера */
    public void tick(MinecraftServer server) {
        final ServerWorld overworld = server.getOverworld();
        if (overworld == null) return;

        // Автоспавн, когда разлом НЕ активен
        if (!active) {
            long time = overworld.getTime();
            if (time >= nextAutoSpawnAt) {
                ServerPlayerEntity target = pickRandomPlayer(server);
                if (target != null) {
                    createNear(target); // это назначит новый nextAutoSpawnAt при закрытии
                } else {
                    // игроков нет — перенесём попытку ещё на минуту
                    nextAutoSpawnAt = time + MINUTE;
                }
            }
            return;
        }

        // Разлом активен — тикаем таймер
        if (ticksLeft > 0) {
            ticksLeft--;
            // уведомления
            maybeAnnounce(server);
        } else {
            // время вышло
            despawn(server, false);
        }
    }

    /** создать новый разлом рядом с игроком (случайно в радиусе, но на уровне игрока ±5 и строго на блоке) */
    public void createNear(ServerPlayerEntity player) {
        ServerWorld w = player.getServerWorld();

        int dist = MathHelper.nextInt(rnd, NEAR_MIN_DIST, NEAR_MAX_DIST);
        int angle = MathHelper.nextInt(rnd, 0, 359);
        double rad = Math.toRadians(angle);
        int dx = (int) Math.round(Math.cos(rad) * dist);
        int dz = (int) Math.round(Math.sin(rad) * dist);

        int baseY = player.getBlockPos().getY();
        int wantY = baseY + MathHelper.nextInt(rnd, -PLAYER_Y_DELTA, PLAYER_Y_DELTA);

        BlockPos candidate = new BlockPos(player.getBlockPos().getX() + dx, wantY, player.getBlockPos().getZ() + dz);
        BlockPos surface = findSolidSurfaceNearY(w, candidate);

        startRift(w, surface, player.getUuid());
    }

    /** принудительно рядом (на 3 блока вправо от игрока; та же обработка поверхности) */
    public void forceNear(ServerPlayerEntity player) {
        ServerWorld w = player.getServerWorld();
        int baseY = player.getBlockPos().getY();
        int wantY = baseY + MathHelper.nextInt(rnd, -PLAYER_Y_DELTA, PLAYER_Y_DELTA);
        BlockPos candidate = new BlockPos(player.getBlockPos().getX() + 3, wantY, player.getBlockPos().getZ());
        BlockPos surface = findSolidSurfaceNearY(w, candidate);
        startRift(w, surface, player.getUuid());
    }

    /** закрыть текущий разлом без сообщения */
    public void despawn(boolean silent) {
        // no-op без сервера — перегрузка ниже делает красивый бродкаст
        this.active = false;
        this.anchor = null;
        this.ticksLeft = 0;
        this.activatorUuid = null;
        resetAnnouncements();
    }

    /** закрыть текущий разлом; при silent=false — сообщение в чат */
    public void despawn(MinecraftServer server, boolean silent) {
        if (!silent && active) {
            broadcast(server, Text.literal("Разлом закрыт.").formatted(Formatting.GRAY));
        }
        this.active = false;
        this.anchor = null;
        this.ticksLeft = 0;
        this.activatorUuid = null;
        resetAnnouncements();

        // назначим следующее окно автоспавна 4–5 минут от текущего времени
        long now = Objects.requireNonNull(server.getOverworld()).getTime();
        int delay = MathHelper.nextInt(rnd, AUTOSPAWN_MIN, AUTOSPAWN_MAX);
        nextAutoSpawnAt = now + delay;
    }

    /** клик по руническому обсидиану */
    public void onBlockActivated(ServerWorld world, BlockPos pos, PlayerEntity player) {
        int baseY = player.getBlockPos().getY();
        int wantY = baseY + MathHelper.nextInt(rnd, -PLAYER_Y_DELTA, PLAYER_Y_DELTA);
        BlockPos candidate = new BlockPos(pos.getX(), wantY, pos.getZ());
        BlockPos surface = findSolidSurfaceNearY(world, candidate);
        startRift(world, surface, player.getUuid());
    }

    // ---- внутренняя реализация ----

    private void startRift(ServerWorld world, BlockPos pos, UUID activator) {
        this.anchor = pos.toImmutable();
        this.activatorUuid = activator;
        this.active = true;
        this.ticksLeft = RIFT_LIFETIME;
        resetAnnouncements();

        broadcast(world.getServer(),
                Text.literal("Открылся разлом у X=" + pos.getX() + " Y=" + pos.getY() + " Z=" + pos.getZ())
                        .formatted(Formatting.LIGHT_PURPLE));

        // мгновенно породим одного моба ради демонстрации (можешь заменить на свой тип)
        spawnMobAround(world, pos);
    }

    private void spawnMobAround(ServerWorld world, BlockPos center) {
        // случайное смещение по XZ, высоту держим около якоря (±2)
        int dx = MathHelper.nextInt(rnd, -6, 6);
        int dz = MathHelper.nextInt(rnd, -6, 6);
        int wantY = center.getY() + MathHelper.nextInt(rnd, -2, 2);

        BlockPos candidate = new BlockPos(center.getX() + dx, wantY, center.getZ() + dz);
        BlockPos at = findSolidSurfaceNearY(world, candidate);

        EntityType<?> type = EntityType.ZOMBIE; // замени на свой EntityType при необходимости
        Entity e = type.spawn(world, at, SpawnReason.EVENT);
        if (e instanceof MobEntity mob) {
            if (activatorUuid != null) {
                var nearest = world.getClosestPlayer(at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5, 32, false);
                if (nearest != null) mob.setTarget(nearest);
            }
        }
    }

    /** Ищет твёрдый блок поблизости от желаемой Y (вначале вниз, затем вверх). Возвращает позицию ВОЗДУХА над твёрдым блоком. */
    private BlockPos findSolidSurfaceNearY(ServerWorld world, BlockPos want) {
        int bottom = world.getBottomY() + 1;
        int top = world.getTopY() - 2;

        // 1) стартуем в разумных границах
        int y = MathHelper.clamp(want.getY(), bottom, top);
        BlockPos.Mutable m = new BlockPos.Mutable(want.getX(), y, want.getZ());

        // 2) поиск ВНИЗ (предпочтительно, чтобы не улетать под -60/void)
        for (int i = 0; i <= SURFACE_SEARCH_DOWN && m.getY() > bottom; i++) {
            if (isSolidFloorWithHeadroom(world, m.down())) {
                return m.toImmutable(); // m сейчас — воздух над полом
            }
            m.move(Direction.DOWN);
        }

        // 3) если не нашли вниз — ищем ВВЕРХ (на случай если стартнули внутри массива)
        m.setY(y);
        for (int i = 0; i <= SURFACE_SEARCH_UP && m.getY() < top; i++) {
            if (isSolidFloorWithHeadroom(world, m.down())) {
                return m.toImmutable();
            }
            m.move(Direction.UP);
        }

        // 4) запасной вариант: позиция игрока по поверхности мира (колонка высоты)
        int surfaceY = world.getTopY(); // верх мира
        BlockPos fallback = new BlockPos(want.getX(), surfaceY, want.getZ());
        // Спустимся до первого твердого
        BlockPos.Mutable fm = fallback.mutableCopy();
        while (fm.getY() > bottom && world.isAir(fm)) fm.move(Direction.DOWN);
        // поднимемся на воздух над полом
        while (fm.getY() < top && !world.isAir(fm)) fm.move(Direction.UP);
        return fm.toImmutable();
    }

    /** true если под этой позицией твёрдый блок, а сама позиция и блок выше — воздух (куда можно поставить/заcпаунить) */
    private boolean isSolidFloorWithHeadroom(ServerWorld world, BlockPos floor) {
        BlockPos head = floor.up();
        BlockPos head2 = head.up();
        return !world.isAir(floor) && world.isAir(head) && world.isAir(head2);
    }

    private void broadcast(MinecraftServer server, Text message) {
        server.getPlayerManager().broadcast(message, false);
    }

    private ServerPlayerEntity pickRandomPlayer(MinecraftServer server) {
        List<ServerPlayerEntity> list = server.getPlayerManager().getPlayerList();
        if (list.isEmpty()) return null;
        return list.get(MathHelper.nextInt(rnd, 0, list.size() - 1));
    }

    private void resetAnnouncements() {
        sent3m = false;
        sent1m = false;
        sent30s = false;
        didFinalCountdown = false;
    }

    /** сообщения: 3мин, 1мин, 30с, затем 5..0 */
    private void maybeAnnounce(MinecraftServer server) {
        if (!active) return;

        if (!sent3m && ticksLeft == 3 * MINUTE) {
            sent3m = true;
            broadcast(server, Text.literal("Разлом закроется через 3 мин.").formatted(Formatting.GOLD));
        }
        if (!sent1m && ticksLeft == 1 * MINUTE) {
            sent1m = true;
            broadcast(server, Text.literal("Разлом закроется через 1 мин.").formatted(Formatting.GOLD));
        }
        if (!sent30s && ticksLeft == 30 * TPS) {
            sent30s = true;
            broadcast(server, Text.literal("Разлом закроется через 30 сек.").formatted(Formatting.YELLOW));
        }

        // финальный отсчёт 5..0
        if (!didFinalCountdown) {
            if (ticksLeft == 5 * TPS) broadcast(server, Text.literal("5").formatted(Formatting.RED));
            if (ticksLeft == 4 * TPS) broadcast(server, Text.literal("4").formatted(Formatting.RED));
            if (ticksLeft == 3 * TPS) broadcast(server, Text.literal("3").formatted(Formatting.RED));
            if (ticksLeft == 2 * TPS) broadcast(server, Text.literal("2").formatted(Formatting.RED));
            if (ticksLeft == 1 * TPS) broadcast(server, Text.literal("1").formatted(Formatting.RED));
            if (ticksLeft == 0) {
                broadcast(server, Text.literal("0").formatted(Formatting.RED));
                didFinalCountdown = true;
            }
        }
    }
}
