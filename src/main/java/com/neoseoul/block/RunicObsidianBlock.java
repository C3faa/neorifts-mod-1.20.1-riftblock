package com.neoseoul.block;

import com.neoseoul.rift.RiftManager;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public class RunicObsidianBlock extends Block {
    public RunicObsidianBlock(Settings settings) {
        super(settings);
    }

    @Override
    @SuppressWarnings("deprecation")
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        if (!world.isClient) {
            RiftManager.get(((ServerWorld) world).getServer())
                    .onBlockActivated((ServerWorld) world, pos, player);
        }
        return ActionResult.SUCCESS;
    }
}
