package com.columbina;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

/** Clic derecho = Habilidad elemental. Agachado + clic derecho = Ráfaga. */
public class ColumbinaStaffItem extends Item {
    public ColumbinaStaffItem(Settings settings) {
        super(settings);
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity player, Hand hand) {
        ItemStack stack = player.getStackInHand(hand);
        if (!world.isClient && player instanceof ServerPlayerEntity sp) {
            boolean ok = player.isSneaking() ? ColumbinaAbilities.burst(sp) : ColumbinaAbilities.skill(sp);
            if (!ok) return TypedActionResult.fail(stack);
        }
        return TypedActionResult.success(stack, world.isClient);
    }
}
