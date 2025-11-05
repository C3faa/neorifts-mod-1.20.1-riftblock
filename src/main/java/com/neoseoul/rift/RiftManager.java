package com.neoseoul.rift;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameRules;
import net.minecraft.world.World;
import net.minecraft.world.biome.BiomeKeys;

import java.util.Optional;
import java.util.UUID;

/**
 * Менеджер рифтов.
 *  - Каждый 5 минут пытается заспавнить один рифт на расстоянии 15..40 блоков от игрока
 *    (по высоте в пределах ±5), только на твёрдом блоке, не на воде/в воздухе.
 *  - Активация по ПКМ на блоке рифта -> 3 волны доккеби: 3 / 4 / 5.
 *  - Удаление рифта, если прошло 5 минут или игрок ушёл дальше чем на 100 блоков.
 *  - Бафы мобов от уровня игрока: каждые 10 уровней +5% урон, +10% хп, +2% скорость.
 *  - На каждом 10 уровне выводится "Мобы стали сильнее".
 */
public final class RiftManager {

    // --------- Синглтон на сервер ---------
    private static RiftManager INSTANCE;

    public static RiftManager get(MinecraftServer server) {
        if (INSTANCE == null) {
            INSTANCE = new RiftManager(server);
        }
        return INSTANCE;
    }

    // --------- Константы логики ---------
    private static final int TICKS_PER_SECOND = 20;
    private static final int RIFT_TRY_PERIOD_TICKS = 5 * 60 * TICKS_PER_SECOND; // 5 минут
    private static final int RIFT_LIFETIME_TICKS = 5 * 60 * TICKS_PER_SECOND;   // 5 минут
    private static final int RIFT_MIN_DIST = 15;
    private static final int RIFT_MAX_DIST = 40;
    private static final int RIFT_DESPAWN_DISTANCE = 100;
    private static final int RIFT_Y_TOLERANCE = 5;

    // --------- Id блок/моб ---------
    private static final Identifier RUNIC_OBSIDIAN_ID = new Identifier("neorifts", "runic_obsidian");
    private static final Identifier DOKKEBI_ID = new Identifier("neoseoul", "dokkebi"); // поменяйте при другом id

    // --------- Состояние ---------
    private final MinecraftServer server;

    private BlockPos activeRiftPos = null;
    private ServerWorld activeWorld = null;
    private long riftSpawnGameTime = 0L;

    private boolean wavesStarted = false;
    private int currentWave = 0; // 0=не началась, 1..3
    private long nextWaveAtGameTime = 0L;

    private UUID activatorUuid = null;

    private long nextAutoSpawnTryAt = 0L;

    private RiftManager(MinecraftServer server) {
        this.server = server;

        // тики мира
        ServerTickEvents.END_WORLD_TICK.register(this::onWorldTick);
    }

    // ======================================
    //     API из блока (ПКМ по рифту)
    // ======================================
    public void onBlockActivated(ServerWorld world, BlockPos pos, PlayerEntity player) {
        // активация только по активному рифту
        if (activeRiftPos == null || !pos.equals(activeRiftPos) || world != activeWorld) {
            return;
        }
        if (wavesStarted) return;

        wavesStarted = true;
        currentWave = 0;
        activatorUuid = player.getUuid();
        // сразу запускаем первую волну
        startNextWave(world);
    }

    // ======================================
    //              ТИКИ МИРА
    // ======================================
    private void onWorldTick(ServerWorld world) {
        final long now = world.getTime();

        // авто-спавн раз в 5 мин (если вообще нет рифта)
        if (world.getRegistryKey() == World.OVERWORLD) {
            if (now >= nextAutoSpawnTryAt) {
                nextAutoSpawnTryAt = now + RIFT_TRY_PERIOD_TICKS;
                tryAutoSpawnRift(world);
            }
        }

        // если этот мир содержит активный рифт — обслуживаем его
        if (activeWorld == world && activeRiftPos != null) {
            maintainRift(world, now);
        }
    }

    // ======================================
    //         ЛОГИКА РИФТА (ЖИЗНЕННЫЙ ЦИКЛ)
    // ======================================

    private void tryAutoSpawnRift(ServerWorld world) {
        if (activeRiftPos != null) return; // уже есть рифт где-то

        // найдём ближайшего игрока, от которого будем раскладывать точки
        ServerPlayerEntity p = getAnyServerPlayer(world);
        if (p == null) return;

        BlockPos candidate = findRiftSpawnPosNearPlayer(world, p, RIFT_MIN_DIST, RIFT_MAX_DIST, RIFT_Y_TOLERANCE);
        if (candidate == null) return;

        placeRiftBlock(world, candidate);
        activeRiftPos = candidate;
        activeWorld = world;
        riftSpawnGameTime = world.getTime();
        wavesStarted = false;
        currentWave = 0;
        nextWaveAtGameTime = 0L;
        activatorUuid = null;

        sendActionBar(p, "Рядом появился разлом");
        world.playSound(null, candidate, SoundEvents.BLOCK_ENCHANTMENT_TABLE_USE, net.minecraft.sound.SoundCategory.BLOCKS, 1.0f, 1.0f);
    }

    private void maintainRift(ServerWorld world, long now) {
        // удаление через 5 минут
        if (now - riftSpawnGameTime >= RIFT_LIFETIME_TICKS) {
            despawnRift(world, false);
            return;
        }

        // удаление при уходе игрока > 100 блоков
        PlayerEntity ref = world.getClosestPlayer(activeRiftPos.getX() + 0.5, activeRiftPos.getY() + 0.5, activeRiftPos.getZ() + 0.5,
                RIFT_DESPAWN_DISTANCE, false);
        if (ref == null) {
            despawnRift(world, false);
            return;
        }

        // волны: если уже активированы, ждём таймер
        if (wavesStarted && now >= nextWaveAtGameTime && currentWave > 0 && currentWave <= 3) {
            // время следующего чекпоинта (если волна завершена)
            if (isWaveCleared(world)) {
                startNextWave(world);
            }
        }
    }

    private void despawnRift(ServerWorld world, boolean showClearedMessage) {
        if (activeRiftPos != null) {
            // убрать блок
            world.setBlockState(activeRiftPos, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);

            // убрать частицы/служебные сущности возле рифта (если есть)
            Box box = Box.from(Vec3d.ofCenter(activeRiftPos)).expand(2.0);
            for (Entity e : world.getOtherEntities(null, box)) {
                // ничего специального не создавали — чистим только неблокирующие "хвосты" при необходимости
                // оставлено пустым специально
            }

            // по желанию — сообщение при зачистке
            if (showClearedMessage) {
                // сообщение всем рядом
                for (ServerPlayerEntity sp : world.getPlayers()) {
                    if (sp.getBlockPos().isWithinDistance(activeRiftPos, 64)) {
                        sp.sendMessage(Text.literal("Разлом зачищен"), true);
                    }
                }
            }
        }

        // сброс
        activeRiftPos = null;
        activeWorld = null;
        riftSpawnGameTime = 0L;
        wavesStarted = false;
        currentWave = 0;
        nextWaveAtGameTime = 0L;
        activatorUuid = null;
    }

    // ======================================
    //                 ВОЛНЫ
    // ======================================

    private void startNextWave(ServerWorld world) {
        if (activeRiftPos == null) return;

        currentWave++;
        if (currentWave > 3) {
            // всё — выдаём награду и закрываем
            grantRewards(world);
            despawnRift(world, true);
            return;
        }

        int count = switch (currentWave) {
            case 1 -> 3;
            case 2 -> 4;
            case 3 -> 5;
            default -> 0;
        };

        // спавним вокруг рифта
        for (int i = 0; i < count; i++) {
            BlockPos spawn = pickNearbySpawn(world, activeRiftPos, 4, 8);
            spawnDokkebi(world, spawn);
        }

        // ждём очистки волны — проверка каждые 2 сек
        nextWaveAtGameTime = world.getTime() + 2 * TICKS_PER_SECOND;
    }

    private boolean isWaveCleared(ServerWorld world) {
        if (activeRiftPos == null) return true;
        // считаем выживших доккеби в радиусе 32
        Box box = Box.from(Vec3d.ofCenter(activeRiftPos)).expand(32.0);
        return world.iterateEntities().noneMatch(e -> isDokkebi(e) && e.getWorld() == world && e.getBoundingBox().intersects(box));
    }

    private boolean isDokkebi(Entity e) {
        // если у вас свой класс — можете заменить на (e instanceof DokkebiEntity)
        // здесь проверяем id через реестр
        Identifier id = Registries.ENTITY_TYPE.getId(e.getType());
        return id != null && id.equals(DOKKEBI_ID);
    }

    private void grantRewards(ServerWorld world) {
        if (activatorUuid == null) return;
        ServerPlayerEntity sp = world.getServer().getPlayerManager().getPlayer(activatorUuid);
        if (sp == null) return;

        // изумруды с тегом "KpopCoin" (простая демонстрация — обычный стак + дисплейнейм)
        ItemStack emeralds = new ItemStack(Items.EMERALD, 5 + world.getRandom().nextInt(6)); // 5..10
        emeralds.setCustomName(Text.literal("KpopCoin"));
        sp.getInventory().insertStack(emeralds);

        // опыт
        sp.addExperience(50);

        // при каждом 10 уровне — сообщение
        int lvl = sp.experienceLevel;
        if (lvl > 0 && (lvl % 10 == 0)) {
            sp.sendMessage(Text.literal("Мобы стали сильнее"), true);
        }
    }

    // ======================================
    //           СПАВН ДОККЕБИ
    // ======================================

    private void spawnDokkebi(ServerWorld world, BlockPos pos) {
        EntityType<? extends MobEntity> type = getDokkebiType();
        MobEntity mob = type.create(world);
        if (mob == null) return;

        mob.refreshPositionAndAngles(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, world.getRandom().nextFloat() * 360f, 0f);

        // масштабируем статы от уровня игрока-активатора
        if (activatorUuid != null && mob instanceof LivingEntity living) {
            ServerPlayerEntity sp = world.getServer().getPlayerManager().getPlayer(activatorUuid);
            if (sp != null) {
                applyLevelScaling(living, sp.experienceLevel);
            }
        }

        world.spawnEntity(mob);
    }

    @SuppressWarnings("unchecked")
    private EntityType<? extends MobEntity> getDokkebiType() {
        Optional<EntityType<?>> opt = Registries.ENTITY_TYPE.getOrEmpty(DOKKEBI_ID);
        if (opt.isPresent() && opt.get() instanceof EntityType<?> t) {
            return (EntityType<? extends MobEntity>) t;
        }
        // Fallback — чтобы сборка не падала, если ваш entity не зарегистрирован
        return (EntityType<? extends MobEntity>) EntityType.ZOMBIE;
    }

    private void applyLevelScaling(LivingEntity mob, int level) {
        if (level <= 0) return;
        int steps = level / 10; // каждые 10 уровней

        if (steps <= 0) return;

        // +10% HP за шаг
        double baseMax = mob.getMaxHealth();
        double scaledMax = baseMax * (1.0 + 0.10 * steps);
        mob.getAttributes().getCustomInstance(net.minecraft.entity.attribute.EntityAttributes.GENERIC_MAX_HEALTH)
                .setBaseValue(scaledMax);
        mob.setHealth((float) scaledMax);

        // +5% урон за шаг (если есть атрибут)
        if (mob.getAttributes().hasAttribute(net.minecraft.entity.attribute.EntityAttributes.GENERIC_ATTACK_DAMAGE)) {
            double base = mob.getAttributes().getValue(net.minecraft.entity.attribute.EntityAttributes.GENERIC_ATTACK_DAMAGE);
            mob.getAttributes().getCustomInstance(net.minecraft.entity.attribute.EntityAttributes.GENERIC_ATTACK_DAMAGE)
                    .setBaseValue(base * (1.0 + 0.05 * steps));
        }

        // +2% скорость за шаг
        if (mob.getAttributes().hasAttribute(net.minecraft.entity.attribute.EntityAttributes.GENERIC_MOVEMENT_SPEED)) {
            double base = mob.getAttributes().getValue(net.minecraft.entity.attribute.EntityAttributes.GENERIC_MOVEMENT_SPEED);
            mob.getAttributes().getCustomInstance(net.minecraft.entity.attribute.EntityAttributes.GENERIC_MOVEMENT_SPEED)
                    .setBaseValue(base * (1.0 + 0.02 * steps));
        }
    }

    // ======================================
    //           ПОИСК ПОЗИЦИИ ДЛЯ РИФТА
    // ======================================

    private BlockPos findRiftSpawnPosNearPlayer(ServerWorld world, ServerPlayerEntity p, int minDist, int maxDist, int yTolerance) {
        Block runicBlock = Registries.BLOCK.getOrEmpty(RUNIC_OBSIDIAN_ID).orElse(Blocks.BEDROCK);

        RandomSource rnd = world.getRandom();
        BlockPos base = p.getBlockPos();
        int baseY = base.getY();

        for (int tries = 0; tries < 64; tries++) {
            double angle = rnd.nextDouble() * Math.PI * 2.0;
            int dist = MathHelper.nextBetween(rnd, minDist, maxDist);
            int dx = (int) Math.round(Math.cos(angle) * dist);
            int dz = (int) Math.round(Math.sin(angle) * dist);

            int y = baseY + MathHelper.nextBetween(rnd, -yTolerance, yTolerance);
            BlockPos pos = new BlockPos(base.getX() + dx, y, base.getZ() + dz);

            if (!isGoodRiftSpot(world, pos)) continue;

            // гарантированно твёрдая подложка
            BlockPos below = pos.down();
            BlockState belowState = world.getBlockState(below);
            if (belowState.isAir()) continue;
            if (belowState.getFluidState() != null && !belowState.getFluidState().isEmpty()) continue;

            // нашли
            return pos;
        }
        return null;
    }

    private boolean isGoodRiftSpot(ServerWorld world, BlockPos pos) {
        // место свободно
        if (!world.getBlockState(pos).isAir()) return false;
        // не вода
        if (!world.getFluidState(pos).isEmpty()) return false;

        // сверху тоже свободно (на всякий)
        if (!world.getBlockState(pos.up()).isAir()) return false;

        return true;
    }

    private void placeRiftBlock(ServerWorld world, BlockPos pos) {
        Block runic = Registries.BLOCK.getOrEmpty(RUNIC_OBSIDIAN_ID).orElse(Blocks.BEDROCK);
        world.setBlockState(pos, runic.getDefaultState(), Block.NOTIFY_ALL);
    }

    private BlockPos pickNearbySpawn(ServerWorld world, BlockPos center, int min, int max) {
        RandomSource rnd = world.getRandom();
        for (int i = 0; i < 32; i++) {
            double a = rnd.nextDouble() * Math.PI * 2.0;
            int d = MathHelper.nextBetween(rnd, min, max);
            int x = center.getX() + (int) Math.round(Math.cos(a) * d);
            int z = center.getZ() + (int) Math.round(Math.sin(a) * d);
            int y = center.getY();

            BlockPos p = new BlockPos(x, y, z);
            if (world.getBlockState(p).isAir() && world.getBlockState(p.down()).isSolidBlock(world, p.down())) {
                return p;
            }
        }
        return center.up();
    }

    private ServerPlayerEntity getAnyServerPlayer(ServerWorld world) {
        // сначала ближайший к спавну мира
        ServerPlayerEntity closest = world.getClosestPlayer(world.getSpawnPos().getX() + 0.5, world.getSpawnPos().getY() + 0.5, world.getSpawnPos().getZ() + 0.5, 128, false);
        if (closest != null) return closest;
        // или любой онлайн
        return world.getPlayers().isEmpty() ? null : world.getPlayers().get(0);
    }

    private static void sendActionBar(ServerPlayerEntity p, String msg) {
        p.sendMessage(Text.literal(msg), true);
    }
}
