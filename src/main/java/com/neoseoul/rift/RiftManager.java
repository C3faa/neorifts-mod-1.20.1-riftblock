package com.neoseoul.rift;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
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
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Менеджер рифтов:
 * - авто-спавн 1 рифта каждые 5 минут на дистанции 15..40 от игрока (±5 по Y), только на твёрдом блоке;
 * - активация ПКМ по блоку рифта -> 3 волны доккеби (3/4/5);
 * - удаление рифта через 5 минут или если игрок ушёл дальше 100 блоков;
 * - бафы мобов каждые 10 уровней игрока: +5% урон, +10% хп, +2% скорость;
 * - сообщение "Мобы стали сильнее" на каждом 10 уровне.
 */
public final class RiftManager {

    private static RiftManager INSTANCE;

    public static RiftManager get(MinecraftServer server) {
        if (INSTANCE == null) {
            INSTANCE = new RiftManager(server);
        }
        return INSTANCE;
    }

    // ==== тайминги/радиусы ====
    private static final int TPS = 20;
    private static final int RIFT_TRY_PERIOD = 5 * 60 * TPS;   // 5 минут
    private static final int RIFT_LIFETIME   = 5 * 60 * TPS;   // 5 минут
    private static final int MIN_DIST = 15;
    private static final int MAX_DIST = 40;
    private static final int DESPAWN_DIST = 100;
    private static final int Y_TOLERANCE = 5;

    // ==== id блока/сущности ====
    private static final Identifier RUNIC_OBSIDIAN_ID = new Identifier("neorifts", "runic_obsidian"); // поменяйте при другом id
    private static final Identifier DOKKEBI_ID        = new Identifier("neoseoul", "dokkebi");        // поменяйте при другом id

    // ==== состояние ====
    private final MinecraftServer server;

    private BlockPos activeRiftPos = null;
    private ServerWorld activeWorld = null;
    private long riftSpawnGameTime = 0L;

    private boolean wavesStarted = false;
    private int currentWave = 0; // 0=нет, 1..3
    private long nextWaveCheckAt = 0L;

    private UUID activatorUuid = null;

    private long nextAutoSpawnTryAt = 0L;

    private RiftManager(MinecraftServer server) {
        this.server = server;
        ServerTickEvents.END_WORLD_TICK.register(this::onWorldTick);
    }

    // =======================
    //  Вызов из блока (ПКМ)
    // =======================
    public void onBlockActivated(ServerWorld world, BlockPos pos, PlayerEntity player) {
        if (activeRiftPos == null || world != activeWorld || !activeRiftPos.equals(pos)) return;
        if (wavesStarted) return;

        wavesStarted = true;
        currentWave = 0;
        activatorUuid = player.getUuid();
        startNextWave(world);
    }

    // ==============
    //   ТИК МИРА
    // ==============
    private void onWorldTick(ServerWorld world) {
        long now = world.getTime();

        // Авто-спавн только в верхнем мире и если рифта нет
        if (world.getRegistryKey() == World.OVERWORLD) {
            if (activeRiftPos == null && now >= nextAutoSpawnTryAt) {
                nextAutoSpawnTryAt = now + RIFT_TRY_PERIOD;
                tryAutoSpawnRift(world);
            }
        }

        if (world == activeWorld && activeRiftPos != null) {
            maintainRift(world, now);
        }
    }

    // ==========================
    //   Жизненный цикл рифта
    // ==========================
    private void tryAutoSpawnRift(ServerWorld world) {
        if (activeRiftPos != null) return;

        ServerPlayerEntity p = getAnyServerPlayer(world);
        if (p == null) return;

        BlockPos candidate = findRiftSpawnPosNearPlayer(world, p, MIN_DIST, MAX_DIST, Y_TOLERANCE);
        if (candidate == null) return;

        placeRiftBlock(world, candidate);
        activeRiftPos = candidate;
        activeWorld = world;
        riftSpawnGameTime = world.getTime();

        wavesStarted = false;
        currentWave = 0;
        nextWaveCheckAt = 0L;
        activatorUuid = null;

        sendActionBar(p, "Рядом появился разлом");
        world.playSound(null, candidate, SoundEvents.BLOCK_ENCHANTMENT_TABLE_USE, net.minecraft.sound.SoundCategory.BLOCKS, 1.0f, 1.0f);
    }

    private void maintainRift(ServerWorld world, long now) {
        // Время жизни
        if (now - riftSpawnGameTime >= RIFT_LIFETIME) {
            despawnRift(world, false);
            return;
        }

        // Игрок далеко
        PlayerEntity near = world.getClosestPlayer(activeRiftPos.getX() + 0.5, activeRiftPos.getY() + 0.5, activeRiftPos.getZ() + 0.5,
                DESPAWN_DIST, false);
        if (near == null) {
            despawnRift(world, false);
            return;
        }

        // Волны: проверка очистки
        if (wavesStarted && currentWave > 0 && currentWave <= 3 && now >= nextWaveCheckAt) {
            if (isWaveCleared(world)) {
                startNextWave(world);
            } else {
                // следующая проверка через 2 сек
                nextWaveCheckAt = now + 2 * TPS;
            }
        }
    }

    private void despawnRift(ServerWorld world, boolean showClearedMessage) {
        if (activeRiftPos != null) {
            world.setBlockState(activeRiftPos, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);

            if (showClearedMessage) {
                for (ServerPlayerEntity sp : world.getPlayers()) {
                    if (sp.getBlockPos().isWithinDistance(activeRiftPos, 64)) {
                        sp.sendMessage(Text.literal("Разлом зачищен"), true);
                    }
                }
            }
        }

        activeRiftPos = null;
        activeWorld = null;
        riftSpawnGameTime = 0L;
        wavesStarted = false;
        currentWave = 0;
        nextWaveCheckAt = 0L;
        activatorUuid = null;
    }

    // ==========
    //  Волны
    // ==========
    private void startNextWave(ServerWorld world) {
        if (activeRiftPos == null) return;

        currentWave++;
        if (currentWave > 3) {
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

        for (int i = 0; i < count; i++) {
            BlockPos spawn = pickNearbySpawn(world, activeRiftPos, 4, 8);
            spawnDokkebi(world, spawn);
        }

        nextWaveCheckAt = world.getTime() + 2 * TPS;
    }

    private boolean isWaveCleared(ServerWorld world) {
        if (activeRiftPos == null) return true;
        Box search = Box.of(Vec3d.ofCenter(activeRiftPos), 64, 64, 64);
        List<MobEntity> mobs = world.getEntitiesByClass(MobEntity.class, search, this::isDokkebi);
        return mobs.isEmpty();
    }

    private boolean isDokkebi(Entity e) {
        Identifier id = Registries.ENTITY_TYPE.getId(e.getType());
        return id != null && id.equals(DOKKEBI_ID);
    }

    private void grantRewards(ServerWorld world) {
        if (activatorUuid == null) return;
        ServerPlayerEntity sp = world.getServer().getPlayerManager().getPlayer(activatorUuid);
        if (sp == null) return;

        ItemStack emeralds = new ItemStack(Items.EMERALD, 5 + world.getRandom().nextInt(6)); // 5..10
        emeralds.setCustomName(Text.literal("KpopCoin"));
        sp.getInventory().insertStack(emeralds);

        sp.addExperience(50);

        int lvl = sp.experienceLevel;
        if (lvl > 0 && (lvl % 10 == 0)) {
            sp.sendMessage(Text.literal("Мобы стали сильнее"), true);
        }
    }

    // ===========================
    //   Спавн доккеби/статы
    // ===========================
    private void spawnDokkebi(ServerWorld world, BlockPos pos) {
        EntityType<? extends MobEntity> type = getDokkebiType();
        MobEntity mob = type.create(world);
        if (mob == null) return;

        mob.refreshPositionAndAngles(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, world.getRandom().nextFloat() * 360f, 0f);

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
        return (EntityType<? extends MobEntity>) EntityType.ZOMBIE; // fallback
    }

    private void applyLevelScaling(LivingEntity mob, int level) {
        int steps = level / 10;
        if (steps <= 0) return;

        // MAX_HEALTH
        if (mob.getAttributes().hasAttribute(net.minecraft.entity.attribute.EntityAttributes.GENERIC_MAX_HEALTH)) {
            double baseMax = mob.getMaxHealth();
            double scaledMax = baseMax * (1.0 + 0.10 * steps);
            mob.getAttributeInstance(net.minecraft.entity.attribute.EntityAttributes.GENERIC_MAX_HEALTH)
                    .setBaseValue(scaledMax);
            mob.setHealth((float) scaledMax);
        }

        // ATTACK_DAMAGE
        if (mob.getAttributes().hasAttribute(net.minecraft.entity.attribute.EntityAttributes.GENERIC_ATTACK_DAMAGE)) {
            double base = mob.getAttributeValue(net.minecraft.entity.attribute.EntityAttributes.GENERIC_ATTACK_DAMAGE);
            mob.getAttributeInstance(net.minecraft.entity.attribute.EntityAttributes.GENERIC_ATTACK_DAMAGE)
                    .setBaseValue(base * (1.0 + 0.05 * steps));
        }

        // MOVEMENT_SPEED
        if (mob.getAttributes().hasAttribute(net.minecraft.entity.attribute.EntityAttributes.GENERIC_MOVEMENT_SPEED)) {
            double base = mob.getAttributeValue(net.minecraft.entity.attribute.EntityAttributes.GENERIC_MOVEMENT_SPEED);
            mob.getAttributeInstance(net.minecraft.entity.attribute.EntityAttributes.GENERIC_MOVEMENT_SPEED)
                    .setBaseValue(base * (1.0 + 0.02 * steps));
        }
    }

    // ===========================
    //   Поиск позиции для рифта
    // ===========================
    private BlockPos findRiftSpawnPosNearPlayer(ServerWorld world, ServerPlayerEntity p, int minDist, int maxDist, int yTol) {
        Block runicBlock = Registries.BLOCK.getOrEmpty(RUNIC_OBSIDIAN_ID).orElse(Blocks.BEDROCK);

        Random rnd = world.getRandom();
        BlockPos base = p.getBlockPos();
        int baseY = base.getY();

        for (int tries = 0; tries < 64; tries++) {
            double angle = rnd.nextDouble() * Math.PI * 2.0;
            int dist = MathHelper.nextBetween(rnd, minDist, maxDist);
            int dx = (int) Math.round(Math.cos(angle) * dist);
            int dz = (int) Math.round(Math.sin(angle) * dist);

            int y = baseY + MathHelper.nextBetween(rnd, -yTol, yTol);
            BlockPos pos = new BlockPos(base.getX() + dx, y, base.getZ() + dz);

            if (!isGoodRiftSpot(world, pos)) continue;

            BlockPos below = pos.down();
            BlockState belowState = world.getBlockState(below);
            if (belowState.isAir()) continue;
            if (!belowState.getFluidState().isEmpty()) continue;

            return pos;
        }
        return null;
    }

    private boolean isGoodRiftSpot(ServerWorld world, BlockPos pos) {
        if (!world.getBlockState(pos).isAir()) return false;
        if (!world.getFluidState(pos).isEmpty()) return false;
        if (!world.getBlockState(pos.up()).isAir()) return false;
        return true;
    }

    private void placeRiftBlock(ServerWorld world, BlockPos pos) {
        Block runic = Registries.BLOCK.getOrEmpty(RUNIC_OBSIDIAN_ID).orElse(Blocks.BEDROCK);
        world.setBlockState(pos, runic.getDefaultState(), Block.NOTIFY_ALL);
    }

    private BlockPos pickNearbySpawn(ServerWorld world, BlockPos center, int min, int max) {
        Random rnd = world.getRandom();
        for (int i = 0; i < 32; i++) {
            double a = rnd.nextDouble() * Math.PI * 2.0;
            int d = MathHelper.nextBetween(rnd, min, max);
            int x = center.getX() + (int) Math.round(Math.cos(a) * d);
            int z = center.getZ() + (int) Math.round(Math.sin(a) * d);
            int y = center.getY();

            BlockPos p = new BlockPos(x, y, z);
            if (world.getBlockState(p).isAir()
                    && world.getBlockState(p.down()).isSolidBlock(world, p.down())) {
                return p;
            }
        }
        return center.up();
    }

    private ServerPlayerEntity getAnyServerPlayer(ServerWorld world) {
        List<ServerPlayerEntity> list = world.getPlayers();
        return list.isEmpty() ? null : list.get(0);
    }

    private static void sendActionBar(ServerPlayerEntity p, String msg) {
        p.sendMessage(Text.literal(msg), true);
    }
}
