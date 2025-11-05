package com.neoseoul.rift;

import com.neoseoul.block.ModBlocks;
import com.neoseoul.entity.DokkebiEntity;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameRules;
import net.minecraft.world.World;
import net.minecraft.world.event.GameEvent;

import java.util.*;

public class RiftManager {
    private static final Map<MinecraftServer, RiftManager> INST = new WeakHashMap<>();
    public static void init() {}
    public static RiftManager get(MinecraftServer server) { return INST.computeIfAbsent(server, s -> new RiftManager()); }

    private BlockPos anchor;          // current rift block pos or null
    private int ticks;                // age of current rift
    private int worldCooldown = 20*300; // 5m between spawn attempts
    private boolean wavesStarted = false;
    private int wave = 0;             // 0 not started, 1..3 running
    private final Set<UUID> alive = new HashSet<>();

    public void tick(MinecraftServer server) {
        ServerWorld world = server.getOverworld();
        if (world == null) return;

        if (anchor == null) {
            if (worldCooldown > 0) worldCooldown--;
            if (worldCooldown == 0) trySpawnNearAnyPlayer(world);
            return;
        }

        // ambient particles
        world.spawnParticles(ParticleTypes.END_ROD,
                anchor.getX()+0.5, anchor.getY()+1.0, anchor.getZ()+0.5,
                4, 0.4, 0.6, 0.4, 0.02);

        ticks++;

        // check despawn conditions
        boolean noPlayers = world.getEntitiesByClass(ServerPlayerEntity.class, new Box(anchor).expand(100), p -> true).isEmpty();
        if (noPlayers || ticks > 20*300) { // 5 minutes alive
            despawn(server, false);
            return;
        }

        // wave progression
        if (wavesStarted && wave > 0) {
            alive.removeIf(uuid -> {
                Entity e = world.getEntity(uuid);
                return e == null || !e.isAlive();
            });
            if (alive.isEmpty()) {
                if (wave < 3) startWave(world, wave+1);
                else clearSuccess(world);
            }
        }
    }

    // RIGHT CLICK on rift block -> start waves if not started
    public void onBlockActivated(World w, BlockPos pos, ServerPlayerEntity player) {
        if (!(w instanceof ServerWorld world)) return;
        if (anchor == null || !anchor.equals(pos)) return;
        if (!wavesStarted) {
            startWave(world, 1);
            wavesStarted = true;
        }
    }

    private void trySpawnNearAnyPlayer(ServerWorld world) {
        if (anchor != null) return;
        for (ServerPlayerEntity p : world.getPlayers()) {
            BlockPos base = p.getBlockPos();
            // 24 random tries within 15..40 radius and Y within ±5 of player
            Random rand = new Random(world.getTime());
            for (int i=0;i<24;i++) {
                double ang = rand.nextDouble()*Math.PI*2;
                int dist = 15 + rand.nextInt(26); // 15..40
                int dx = (int)Math.round(Math.cos(ang)*dist);
                int dz = (int)Math.round(Math.sin(ang)*dist);
                int dy = rand.nextInt(11)-5; // -5..+5
                BlockPos pos = base.add(dx, dy, dz);
                if (isValidRiftPos(world, pos)) {
                    placeRift(world, pos);
                    return;
                }
            }
        }
        // didn't find -> retry in 30s
        worldCooldown = 20*30;
    }

    private boolean isValidRiftPos(ServerWorld world, BlockPos pos) {
        // only on solid top, no water, air above, replaceable at pos, not fluid
        BlockPos below = pos.down();
        boolean airAtPos = world.isAir(pos);
        boolean solidBelow = !world.isAir(below) && world.getFluidState(below).isEmpty();
        boolean noFluidAtPos = world.getFluidState(pos).isEmpty();
        return airAtPos && solidBelow && noFluidAtPos;
    }

    private void placeRift(ServerWorld world, BlockPos pos) {
        this.anchor = pos;
        this.ticks = 0;
        this.wavesStarted = false;
        this.wave = 0;
        world.setBlockState(pos, ModBlocks.RUNIC_OBSIDIAN.getDefaultState());
        world.getServer().getPlayerManager().broadcast(Text.literal("§bРядом открылся разлом"), false);
        worldCooldown = 20*300; // reset global cooldown
    }

    private void startWave(ServerWorld world, int num) {
        this.wave = num;
        this.alive.clear();
        int count = (num==1?3: num==2?4:5);

        // choose reference player (nearest within 32, else any within 100, else null)
        ServerPlayerEntity ref = world.getClosestPlayer(anchor.getX()+0.5, anchor.getY()+0.5, anchor.getZ()+0.5, 32, false);
        if (ref == null) ref = world.getClosestPlayer(anchor.getX()+0.5, anchor.getY()+0.5, anchor.getZ()+0.5, 100, false);

        // scaling based on player level (per each 10 levels)
        double level = (ref!=null)? ref.experienceLevel : 0;
        double tiers = Math.floor(level / 10.0);
        double atkMul = 1.0 + tiers * 0.05; // +5% per 10 levels
        double hpMul  = 1.0 + tiers * 0.10; // +10% per 10 levels
        double spdMul = 1.0 + tiers * 0.02; // +2% per 10 levels

        Vec3d c = Vec3d.ofCenter(anchor);
        int[][] deltas = {{1,0},{-1,0},{0,1},{0,-1},{2,0}};
        for (int i=0;i<count;i++) {
            int[] d = deltas[i % deltas.length];
            DokkebiEntity mob = DokkebiEntity.TYPE.create(world);
            if (mob == null) continue;
            mob.refreshPositionAndAngles(c.x + d[0], c.y, c.z + d[1], world.random.nextFloat()*360f, 0);
            // apply scaling
            mob.getAttributeInstance(EntityAttributes.GENERIC_ATTACK_DAMAGE).setBaseValue(2.0D * atkMul);
            mob.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH).setBaseValue(18.0D * hpMul);
            mob.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED).setBaseValue(0.24D * spdMul);
            mob.setHealth(mob.getMaxHealth());
            world.spawnEntity(mob);
            alive.add(mob.getUuid());
        }
        world.getServer().getPlayerManager().broadcast(Text.literal("§6Волна "+num+"/3"), false);
    }

    private void clearSuccess(ServerWorld world) {
        // reward nearest player
        ServerPlayerEntity p = world.getClosestPlayer(anchor.getX()+0.5, anchor.getY()+0.5, anchor.getZ()+0.5, 32, false);
        if (p != null) {
            // KpopCoin emeralds
            ItemStack coins = new ItemStack(Items.EMERALD, 8);
            coins.setCustomName(Text.literal("KpopCoin"));
            p.giveItemStack(coins);
            p.addExperience(120);
        }
        world.getServer().getPlayerManager().broadcast(Text.literal("§aРазлом зачищен"), false);
        despawn(world.getServer(), false);
    }

    public void createNear(ServerPlayerEntity p) {
        ServerWorld w = p.getServerWorld();
        if (anchor != null) return;
        trySpawnNearAnyPlayer(w);
    }

    public void forceNear(ServerPlayerEntity p) {
        ServerWorld w = p.getServerWorld();
        BlockPos pos = p.getBlockPos();
        if (isValidRiftPos(w, pos)) placeRift(w, pos);
        else trySpawnNearAnyPlayer(w);
    }

    public void despawn(MinecraftServer server, boolean manual) {
        if (anchor == null) { worldCooldown = 20*300; return; }
        ServerWorld world = server.getOverworld();
        if (world == null) return;
        // remove block
        if (world.getBlockState(anchor).getBlock() == ModBlocks.RUNIC_OBSIDIAN) {
            world.removeBlock(anchor, false);
        }
        // kill remaining mobs
        List<Entity> list = world.getOtherEntities(null, new Box(anchor).expand(160), e -> alive.contains(e.getUuid()));
        for (Entity e : list) e.discard();
        // reset
        anchor = null;
        ticks = 0;
        wavesStarted = false;
        wave = 0;
        alive.clear();
        worldCooldown = 20*300; // 5 minutes for next spawn
        if (manual) {
            world.getServer().getPlayerManager().broadcast(Text.literal("§7Рифт закрыт"), false);
        }
    }
}
