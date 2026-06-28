package com.wsteam.wandscape.wand.item;

import com.wsteam.wandscape.npc.client.CastBoltParticle;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.component.DataComponents;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
public class WandItem extends Item {

    private static final int CAST_COOLDOWN_TICKS = 8;
    private static final float[] DEFAULT_COLOR = {1.0f, 1.0f, 1.0f};
    private static final double RAY_RANGE = 7.0;
    private static final double RAY_STEP = 0.35;

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
            return InteractionResultHolder.success(stack);
        }

        float[] color = parseWandColor(stack);
        spawnRay((ClientLevel) level, player, hand, color);
        level.playSound(player, player.getX(), player.getY(), player.getZ(),
                SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS,
                0.4f, 1.0f + level.random.nextFloat() * 0.3f);

        return InteractionResultHolder.success(stack);
    }

    private float[] parseWandColor(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data != null && data.contains("wand_color")) {
            String hex = data.copyTag().getString("wand_color");
            if (hex.length() == 7 && hex.charAt(0) == '#') {
                try {
                    int argb = 0xFF000000 | Integer.parseInt(hex.substring(1), 16);
                    return new float[] {
                            ((argb >> 16) & 0xFF) / 255f,
                            ((argb >> 8) & 0xFF) / 255f,
                            (argb & 0xFF) / 255f
                    };
                } catch (NumberFormatException ignored) {}
            }
        }
        return DEFAULT_COLOR;
    }

    private void spawnRay(ClientLevel level, Player player, InteractionHand hand, float[] color) {
        Vec3 look = player.getLookAngle();
        float sideOffset = (hand == InteractionHand.MAIN_HAND) ? 0.3f : -0.3f;
        Vec3 right = look.cross(new Vec3(0, 1, 0)).normalize().scale(sideOffset);
        Vec3 origin = player.getEyePosition()
                .add(look.scale(0.8))
                .add(right)
                .add(0, -0.2, 0);

        // Stationary stars along the beam ray
        for (double d = 0.6; d <= RAY_RANGE; d += RAY_STEP) {
            double px = origin.x + look.x * d;
            double py = origin.y + look.y * d;
            double pz = origin.z + look.z * d;
            CastBoltParticle.spawn(level, px, py, pz, color[0], color[1], color[2]);
        }
    }
}
