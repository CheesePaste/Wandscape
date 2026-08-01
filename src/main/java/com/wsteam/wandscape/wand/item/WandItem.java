package com.wsteam.wandscape.wand.item;

import com.wsteam.wandscape.magic.internal.MagicCaster;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
public class WandItem extends Item {

    private static final int CAST_COOLDOWN_TICKS = 8;

    public WandItem(Properties properties) {
        super(properties);
    }

    @Override
    public boolean isBarVisible(ItemStack stack) {
        return false;
    }

    @Override
    public boolean isDamageable(ItemStack stack) {
        return false;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        if (!level.isClientSide) {
            player.getCooldowns().addCooldown(this, CAST_COOLDOWN_TICKS);
            // 施放攻击魔法阵：发包渲染（垂直于法杖）+ 动画结束后光束射向准星目标
            if (player instanceof ServerPlayer sp) {
                MagicCaster.cast(sp.serverLevel(), sp, MagicCaster.DEFAULT_CIRCLE, null);
            }
            return InteractionResultHolder.success(stack);
        }

        level.playSound(player, player.getX(), player.getY(), player.getZ(),
                SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS,
                0.4f, 1.0f + level.random.nextFloat() * 0.3f);

        return InteractionResultHolder.success(stack);
    }
}
