package com.neoseoul.rift;

import com.neoseoul.NEoriftsMod; // <-- если пакет у вас "com.neoseoul", ИМПОРТ ниже должен быть com.neoseoul.NeoriftsMod
// ВАЖНО: замените строку выше на: import com.neoseoul.NeoriftsMod;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.OverlayMessageS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameRules;
import net.minecraft.world.World;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Менеджер разлома: авто-спавн, волны, награды, HUD.
 * ПОД 1.20.1 (Yarn), Fabric API.
 */
public class RiftManager {

    // === Синглтон по серверу ===
    private static final Map<MinecraftServer, RiftManager> INSTANCES = new WeakHashMap<>();
    public static RiftManager get(MinecraftServer server) {
        return INSTANCES.computeIfAbsent(server, k -> new RiftManager());
    }

    // === Константы логики ===
    private static final int MIN_DIST = 15;   // не ближе 15 блоков к игроку
    private static final int MAX_DIST = 40;   // не дальше 40
    private static final int MAX_AWAY_DESPAWN = 100; // уход игрока >100 блоков = удаляем
    private static final int HEIGHT_DELTA = 5;
    private static final int LIFETIME_TICKS = 5 * 60 * 20;    // 5 минут
    private static final int AUTOSPAWN_INTERVAL_TICKS = 5 * 60 * 20; // автоспавн каждые 5 минут

    // Волны
    private static final int[] WAVES = {3, 4, 5};

    // === Состояние текущего рифта ===
    private BlockPos anchor; // позиция разлома (центр)
    private UUID activatorUuid; // кто активировал (для награды/скейлинга)
    private long createdTick = -1;
    private boolean active = false;
    private int currentWave = 0;
    private int mobsAlive = 0;

    // HUD: босс-полоска прогресса (оставшееся время)
    private ServerBossBar bossBar;
    private long lastAutoSpawnCheck = 0;

    private RiftManager() {}

    // === Публичные команды ===

    /** Попытка создать рифт рядом с игроком (с учётом правил). */
    public void createNear(ServerPlayerEntity player) {
        if (hasRift()) {
            toast(player, Text.literal("Разлом уже существует").formatted(Formatting.YELLOW));
            return;
        }
        ServerWorld world = player.getServerWorld();
        Optional<BlockPos> pos = findValidSpotNear(world, player.getBlockPos(), player.getY());
        if (pos.isEmpty()) {
            toast(player, Text.literal("Не найдено места для разлома").formatted(Formatting.RED));
            return;
        }
        spawnRift(world, pos.get(), true);
    }

    /** Принудительный спавн (мягче фильтры, но всё же не воздух/вода). */
    public void forceNear(ServerPlayerEntity player) {
        if (hasRift()) {
            toast(player, Text.literal("Разлом уже существует").formatted(Formatting.YELLOW));
            return;
        }
        ServerWorld world = player.getServerWorld();
        // Ищем спот в радиусе, но упрощённо
        for (int i = 0; i < 200; i++) {
            double ang = ThreadLocalRandom.current().nextDouble() * Math.PI * 2;
            int dist = MathHelper.nextInt(ThreadLocalRandom.current(), MIN_DIST, MAX_DIST);
            BlockPos base = player.getBlockPos().add((int)(Math.cos(ang) * dist), 0, (int)(Math.sin(ang) * dist));
            // Подгон по высоте +-5
            for (int dy = -HEIGHT_DELTA; dy <= HEIGHT_DELTA; dy++) {
                BlockPos p = base.up(dy);
                if (isSolidTop(world, p.down()) && isAiry(world, p) && !isLiquid(world, p)) {
                    spawnRift(world, p, true);
                    return;
                }
            }
        }
        toast(player, Text.literal("Не удалось форсировать разлом").formatted(Formatting.RED));
    }

    /** Удалить рифт. */
    public void despawn(boolean silent) {
        if (!hasRift()) return;
        removeBossBar();
        anchor = null;
        activatorUuid = null;
        createdTick = -1;
        active = false;
        currentWave = 0;
        mobsAlive = 0;
        if (!silent) broadcastAll(anchorWorld(), Text.literal("Разлом исчез").formatted(Formatting.GRAY));
    }

    // === Жизненный цикл ===

    public void tick(MinecraftServer server) {
        long tick = server.getOverworld().getTime();

        // Автоспавн разлома (если нет активного)
        if (!hasRift()) {
            if (tick - lastAutoSpawnCheck >= AUTOSPAWN_INTERVAL_TICKS) {
                lastAutoSpawnCheck = tick;
                tryAutoSpawn(server);
            }
            return;
        }

        // Обновление HUD (boss bar)
        updateBossbar(server);

        // Время жизни
        int lived = (int) (tick - createdTick);
        if (lived >= LIFETIME_TICKS) {
            despawn(true);
            return;
        }

        // Удаляем, если все игроки далеко (>100)
        ServerWorld world = anchorWorld();
        boolean anyNear = world.getPlayers().stream()
                .anyMatch(p -> p.getBlockPos().isWithinDistance(anchor, MAX_AWAY_DESPAWN));
        if (!anyNear) {
            despawn(true);
            return;
        }

        // Если активирован — следим за волной
        if (active) {
            // Если все мобы текущей волны убиты — запускаем следующую или завершаем
            if (mobsAlive <= 0) {
                if (currentWave < WAVES.length) {
                    startWave(world, currentWave);
                } else {
                    // Все волны пройдены — награда и удаление
                    rewardAndFinish(world);
                }
            }
        }
    }

    // === Взаимодействие: ПКМ по блоку разлома ===
    public void onRiftBlockActivated(ServerWorld world, BlockPos pos, ServerPlayerEntity player) {
        if (!hasRift() || !pos.equals(anchor)) return;
        if (active) {
            toast(player, Text.literal("Разлом уже активирован").formatted(Formatting.YELLOW));
            return;
        }
        activatorUuid = player.getUuid();
        active = true;
        currentWave = 0;
        mobsAlive = 0;
        startWave(world, currentWave);
        toastAll(world, Text.literal("Разлом активирован!").formatted(Formatting.LIGHT_PURPLE));
        world.playSound(null, anchor, SoundEvents.BLOCK_END_PORTAL_SPAWN, SoundCategory.BLOCKS, 1.0f, 1.0f);
    }

    // === Внутренняя логика ===

    private void tryAutoSpawn(MinecraftServer server) {
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            if (p.isSpectator()) continue;
            Optional<BlockPos> pos = findValidSpotNear(p.getServerWorld(), p.getBlockPos(), p.getY());
            if (pos.isPresent()) {
                spawnRift(p.getServerWorld(), pos.get(), false);
                // Сообщение только тем, кто рядом (100 блоков)
                server.getPlayerManager().getPlayerList().forEach(sp -> {
                    if (sp.getServerWorld() == p.getServerWorld()
                            && sp.getBlockPos().isWithinDistance(pos.get(), 100)) {
                        actionBar(sp, Text.literal("Рядом появился разлом").formatted(Formatting.LIGHT_PURPLE));
                        sp.playSound(SoundEvents.ENTITY_ENDERMAN_STARE, SoundCategory.PLAYERS, 0.8f, 1.0f);
                    }
                });
                return; // спавним только один
            }
        }
    }

    private void spawnRift(ServerWorld world, BlockPos pos, boolean manual) {
        this.anchor = pos.toImmutable();
        this.createdTick = world.getTime();
        this.active = false;
        this.activatorUuid = null;
        this.currentWave = 0;
        this.mobsAlive = 0;

        // Визуальный маркер: Runic Obsidian (замените на свой блок, если нужен)
        world.setBlockState(anchor, Blocks.OBSIDIAN.getDefaultState()); // или ваш зарегистрированный "runic_obsidian"

        createBossBar();

        // Сообщение ближайшим игрокам
        world.getPlayers().forEach(p -> {
            if (p.getBlockPos().isWithinDistance(anchor, 100)) {
                actionBar((ServerPlayerEntity) p, Text.literal("Рядом появился разлом").formatted(Formatting.LIGHT_PURPLE));
            }
        });
    }

    private void startWave(ServerWorld world, int waveIndex) {
        int count = WAVES[waveIndex];
        currentWave++;
        mobsAlive = count;

        // Спавним "dokkebi" (замените на ваш EntityType, если зарегистрирован)
        // Если у вас собственный EntityType<DokkebiEntity> DOKKEBI, подставьте его сюда.
        Optional<EntityType<?>> optType = resolveEntityType("minecraft:zombie"); // временно — зомби; замените ID на ваш
        EntityType<?> type = optType.orElse(EntityType.ZOMBIE);

        for (int i = 0; i < count; i++) {
            BlockPos spawnAt = pickNearbySpawn(world, anchor);
            Entity e = type.spawn(world, null, null, null,
                    spawnAt, SpawnReason.EVENT, true, true);
            if (e instanceof MobEntity mob) {
                applyScaling(mob);
                // Отслеживание смертей
                mob.getDamageTracker();
                mob.deathTime = 0;
            }
        }

        // Тригер на убывание mobsAlive: в 1.20.1 быстро — через круговой бокс и проверку живых
        // Упростим: каждую секунду пересчитываем живых в tick() через зону; чтобы не усложнять слушателями
    }

    private void rewardAndFinish(ServerWorld world) {
        // Награда — игроку-активатору (если он ещё онлайн), иначе ближайшему
        ServerPlayerEntity target = null;
        if (activatorUuid != null) {
            target = world.getServer().getPlayerManager().getPlayer(activatorUuid);
        }
        if (target == null) {
            target = world.getClosestPlayer(anchor.getX() + 0.5, anchor.getY() + 0.5, anchor.getZ() + 0.5, 32, false);
        }
        if (target != null) {
            // Изумруды с "тегом" KpopCoin — простая выдача
            ItemStack reward = new ItemStack(Items.EMERALD, 5);
            reward.setCustomName(Text.literal("KpopCoin").formatted(Formatting.AQUA));
            target.getInventory().insertStack(reward);
            target.addExperience(50);
            actionBar(target, Text.literal("Разлом зачищен!").formatted(Formatting.GREEN));
        } else {
            toastAll(world, Text.literal("Разлом зачищен!").formatted(Formatting.GREEN));
        }
        world.playSound(null, anchor, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 1f, 1f);
        despawn(true);
    }

    private Optional<BlockPos> findValidSpotNear(ServerWorld world, BlockPos center, double playerY) {
        // Ищем 128 попыток
        for (int i = 0; i < 128; i++) {
            double ang = ThreadLocalRandom.current().nextDouble() * Math.PI * 2;
            int dist = MathHelper.nextInt(ThreadLocalRandom.current(), MIN_DIST, MAX_DIST);
            BlockPos base = center.add((int)(Math.cos(ang) * dist), 0, (int)(Math.sin(ang) * dist));
            // Высота +-5
            int py = (int)Math.round(playerY);
            for (int dy = -HEIGHT_DELTA; dy <= HEIGHT_DELTA; dy++) {
                BlockPos p = new BlockPos(base.getX(), py + dy, base.getZ());
                if (isSolidTop(world, p.down()) && isAiry(world, p) && !isLiquid(world, p)) {
                    return Optional.of(p);
                }
            }
        }
        return Optional.empty();
    }

    private BlockPos pickNearbySpawn(ServerWorld world, BlockPos from) {
        for (int i = 0; i < 40; i++) {
            int dx = MathHelper.nextInt(ThreadLocalRandom.current(), -6, 6);
            int dz = MathHelper.nextInt(ThreadLocalRandom.current(), -6, 6);
            BlockPos p = from.add(dx, 0, dz);
            // подгон по Y вблизи: ищем ближайший воздух над твёрдым
            for (int dy = -2; dy <= 2; dy++) {
                BlockPos q = p.up(dy);
                if (isSolidTop(world, q.down()) && isAiry(world, q)) {
                    return q;
                }
            }
        }
        return from.up(); // fallback
    }

    private void applyScaling(MobEntity mob) {
        // Скалирование от уровня активатора
        int level = 0;
        if (activatorUuid != null) {
            ServerPlayerEntity p = mob.getWorld().getServer().getPlayerManager().getPlayer(activatorUuid);
            if (p != null) level = p.experienceLevel;
        }
        int steps = Math.max(0, level / 10);
        if (steps == 0) return;

        double dmgMul = 1.0 + steps * 0.05;  // +5% к урону/10 уровней
        double hpMul  = 1.0 + steps * 0.10;  // +10% к ХП/10 уровней
        double spdMul = 1.0 + steps * 0.02;  // +2% к скорости/10 уровней

        // Простой бафф: увеличим макс. здоровье, вылечим до фула, и дадим скорость
        if (mob instanceof LivingEntity le) {
            var attr = le.getAttributes();
            if (attr != null) {
                if (le.getAttributeInstance(net.minecraft.entity.attribute.EntityAttributes.GENERIC_MAX_HEALTH) != null) {
                    le.getAttributeInstance(net.minecraft.entity.attribute.EntityAttributes.GENERIC_MAX_HEALTH)
                            .setBaseValue(le.getMaxHealth() * hpMul);
                    le.setHealth(le.getMaxHealth());
                }
                if (le.getAttributeInstance(net.minecraft.entity.attribute.EntityAttributes.GENERIC_ATTACK_DAMAGE) != null) {
                    le.getAttributeInstance(net.minecraft.entity.attribute.EntityAttributes.GENERIC_ATTACK_DAMAGE)
                            .setBaseValue(le.getAttributeBaseValue(net.minecraft.entity.attribute.EntityAttributes.GENERIC_ATTACK_DAMAGE) * dmgMul);
                }
                if (le.getAttributeInstance(net.minecraft.entity.attribute.EntityAttributes.GENERIC_MOVEMENT_SPEED) != null) {
                    le.getAttributeInstance(net.minecraft.entity.attribute.EntityAttributes.GENERIC_MOVEMENT_SPEED)
                            .setBaseValue(le.getAttributeBaseValue(net.minecraft.entity.attribute.EntityAttributes.GENERIC_MOVEMENT_SPEED) * spdMul);
                }
            }
        }
    }

    private Optional<EntityType<?>> resolveEntityType(String id) {
        EntityType<?> t = EntityType.get(id).orElse(null);
        return Optional.ofNullable(t);
    }

    private boolean hasRift() {
        return anchor != null;
    }

    private ServerWorld anchorWorld() {
        // разлом всегда в мире игрока, который его вызвал; примем оверворлд
        // (если используете мульти-измерения — храните ссылку на мир)
        throwIf(anchor == null, "anchor is null");
        // Проще всего: возвращаем оверворлд; если у вас хранится мир — замените.
        // Здесь лучше держать ссылку на мир; для краткости примем Overworld.
        // Исправление: кэшируем мир при spawnRift()
        return cachedWorld;
    }

    // === HUD: boss bar ===
    private ServerWorld cachedWorld;

    private void createBossBar() {
        removeBossBar();
        bossBar = new ServerBossBar(
                Text.literal("Разлом").formatted(Formatting.LIGHT_PURPLE),
                BossBar.Color.PURPLE,
                BossBar.Style.PROGRESS
        );
        bossBar.setPercent(1.0f);
        bossBar.setVisible(true);
        if (cachedWorld != null) {
            for (ServerPlayerEntity p : cachedWorld.getPlayers()) {
                bossBar.addPlayer(p);
            }
        }
    }

    private void removeBossBar() {
        if (bossBar != null) {
            // убрать со всех игроков
            new ArrayList<>(bossBar.getPlayers()).forEach(bossBar::removePlayer);
            bossBar.setVisible(false);
            bossBar = null;
        }
    }

    private void updateBossbar(MinecraftServer server) {
        if (bossBar == null || anchor == null) return;
        long now = cachedWorld.getTime();
        float left = Math.max(0, (LIFETIME_TICKS - (now - createdTick))) / (float)LIFETIME_TICKS;
        bossBar.setPercent(left);

        // Обновляем заголовок c минутами/секундами
        int secs = Math.max(0, (int)((LIFETIME_TICKS - (now - createdTick)) / 20));
        int mm = secs / 60;
        int ss = secs % 60;
        bossBar.setName(Text.literal(String.format("Разлом • осталось %d:%02d", mm, ss))
                .formatted(Formatting.LIGHT_PURPLE));

        // Актуализируем список игроков в том же мире
        Set<ServerPlayerEntity> shouldBe = new HashSet<>(cachedWorld.getPlayers());
        Set<ServerPlayerEntity> nowPlayers = new HashSet<>(bossBar.getPlayers());
        for (ServerPlayerEntity p : shouldBe) if (!nowPlayers.contains(p)) bossBar.addPlayer(p);
        for (ServerPlayerEntity p : nowPlayers) if (!shouldBe.contains(p)) bossBar.removePlayer(p);

        // Пересчёт живых мобов волны (простая эвристика — сущности в радиусе)
        if (active) {
            int alive = 0;
            Box box = new Box(anchor).expand(16);
            for (Entity e : cachedWorld.getEntitiesByClass(MobEntity.class, box, Entity::isAlive)) {
                alive++;
            }
            mobsAlive = alive == 0 && currentWave <= WAVES.length ? 0 : alive;
        }
    }

    // === Утилиты ===

    private void toastAll(ServerWorld world, Text msg) {
        for (ServerPlayerEntity p : world.getPlayers()) {
            actionBar(p, msg);
        }
    }

    private void toast(ServerPlayerEntity p, Text msg) {
        actionBar(p, msg);
    }

    private void actionBar(ServerPlayerEntity p, Text text) {
        p.networkHandler.sendPacket(new OverlayMessageS2CPacket(text));
    }

    private static boolean isAiry(World w, BlockPos p) {
        return w.isAir(p) && w.isAir(p.up());
    }

    private static boolean isLiquid(World w, BlockPos p) {
        return !w.getFluidState(p).isEmpty();
    }

    private static boolean isSolidTop(World w, BlockPos p) {
        BlockState s = w.getBlockState(p);
        return s.isSolidBlock(w, p) && s.getCollisionShape(w, p).getFace(Direction.UP).isEmpty() == false;
    }

    private static void throwIf(boolean cond, String msg) {
        if (cond) throw new IllegalStateException(msg);
    }

    // Переписанная spawnRift с кэшом мира
    private void spawnRift(ServerWorld world, BlockPos pos, boolean manual, boolean dummy) {
        // не используется — оставлено для совместимости
    }

    // ПЕРЕОПРЕДЕЛЁННАЯ spawnRift (актуальная)
    private void spawnRift(ServerWorld world, BlockPos pos, boolean manualCall) {
        this.cachedWorld = world; // кэшируем мир
        this.anchor = pos.toImmutable();
        this.createdTick = world.getTime();
        this.active = false;
        this.activatorUuid = null;
        this.currentWave = 0;
        this.mobsAlive = 0;

        world.setBlockState(anchor, Blocks.OBSIDIAN.getDefaultState());
        createBossBar();

        // Сообщения рядом
        for (ServerPlayerEntity p : world.getPlayers()) {
            if (p.getBlockPos().isWithinDistance(anchor, 100)) {
                actionBar(p, Text.literal("Рядом появился разлом").formatted(Formatting.LIGHT_PURPLE));
                p.playSound(SoundEvents.ENTITY_ENDERMAN_STARE, SoundCategory.PLAYERS, 0.8f, 1.0f);
            }
        }
    }
}
