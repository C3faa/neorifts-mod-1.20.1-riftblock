package com.neoseoul.entity;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ai.goal.ActiveTargetGoal;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.entity.ai.goal.RevengeGoal;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.ai.goal.WanderAroundGoal;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.LocalDifficulty;
import net.minecraft.world.World;

public class DokkebiEntity extends ZombieEntity {

    public DokkebiEntity(EntityType<? extends ZombieEntity> type, World world) {
        super(type, world);
        this.experiencePoints = 10;
        this.setCanPickUpLoot(false);
    }

    @Override
    protected void initGoals() {
        this.goalSelector.add(0, new SwimGoal(this));
        this.goalSelector.add(2, new MeleeAttackGoal(this, 1.1D, false));
        this.goalSelector.add(5, new WanderAroundGoal(this, 1.0D));
        this.goalSelector.add(7, new LookAtEntityGoal(this, PlayerEntity.class, 16.0F));
        this.targetSelector.add(2, new ActiveTargetGoal<>(this, PlayerEntity.class, true));
        this.targetSelector.add(3, new RevengeGoal(this));
    }

    @Override
    public boolean burnsInDaylight() {
        return false;
    }

    @Override
    protected void initEquipment(Random random, LocalDifficulty difficulty) {
        this.equipStack(EquipmentSlot.HEAD, Items.LEATHER_HELMET.getDefaultStack());
        this.setEquipmentDropChance(EquipmentSlot.HEAD, 0.0f);
    }
}
