package com.neoseoul.entity;

import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.minecraft.entity.*;
import net.minecraft.entity.ai.goal.*;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;

public class DokkebiEntity extends ZombieEntity {

    public static EntityType<DokkebiEntity> TYPE;

    public DokkebiEntity(EntityType<? extends ZombieEntity> type, World world) {
        super(type, world);
        this.experiencePoints = 7;
        this.setCanPickUpLoot(false);
    }

    public static void register() {
        TYPE = Registry.register(Registries.ENTITY_TYPE,
                new Identifier("neorifts", "dokkebi"),
                FabricEntityTypeBuilder.create(SpawnGroup.MONSTER, DokkebiEntity::new)
                        .dimensions(EntityDimensions.fixed(0.6F, 1.95F)).trackRangeBlocks(64)
                        .build());
        FabricDefaultAttributeRegistry.register(TYPE, createAttributes());
    }

    public static DefaultAttributeContainer createAttributes() {
        return ZombieEntity.createZombieAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 18.0D)
                .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 2.0D)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.24D)
                .build();
    }

    @Override protected void initGoals() {
        this.goalSelector.add(0, new SwimGoal(this));
        this.goalSelector.add(2, new MeleeAttackGoal(this, 1.1D, false));
        this.goalSelector.add(5, new WanderAroundGoal(this, 1.0D));
        this.goalSelector.add(7, new LookAtEntityGoal(this, PlayerEntity.class, 16.0F));
        this.targetSelector.add(2, new ActiveTargetGoal<>(this, PlayerEntity.class, true));
        this.targetSelector.add(3, new RevengeGoal(this));
    }

    @Override public boolean burnsInDaylight() { return false; }

    @Override protected void initEquipment(Random random, float difficulty) {
        this.equipStack(EquipmentSlot.HEAD, Items.LEATHER_HELMET.getDefaultStack());
        this.setEquipmentDropChance(EquipmentSlot.HEAD, 0.0f);
    }
}
