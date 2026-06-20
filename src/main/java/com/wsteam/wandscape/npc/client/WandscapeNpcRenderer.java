package com.wsteam.wandscape.npc.client;

import java.util.Optional;

import com.mojang.blaze3d.vertex.PoseStack;
import com.wsteam.wandscape.npc.entity.WandscapeNpc;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

public class WandscapeNpcRenderer extends HumanoidMobRenderer<WandscapeNpc, HumanoidModel<WandscapeNpc>> {

    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath("wandscape", "textures/entity/wizard.png");
    private static final float[] DEFAULT_CAST_COLOR = {1.0f, 1.0f, 1.0f};
    private static final double RAY_RANGE = 5.0;
    private static final double RAY_STEP = 0.4;

    public WandscapeNpcRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new WandscapeNpcModel(ctx.bakeLayer(ModelLayers.PLAYER)), 0.5f);
    }

    @Override
    public ResourceLocation getTextureLocation(WandscapeNpc entity) {
        return TEXTURE;
    }

    @Override
    public void render(WandscapeNpc entity, float entityYaw, float partialTicks,
                       PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        if (entity.isCasting()) {
            spawnCastRay(entity);
        }
        super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight);
    }

    private float[] getWandColor(WandscapeNpc entity) {
        ItemStack mainHand = entity.getMainHandItem();
        if (!mainHand.isEmpty()) {
            CustomData data = mainHand.get(DataComponents.CUSTOM_DATA);
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
        }
        return DEFAULT_CAST_COLOR;
    }

    private void spawnCastRay(WandscapeNpc entity) {
        if (!entity.level().isClientSide) return;

        ClientLevel level = (ClientLevel) entity.level();
        float[] color = getWandColor(entity);

        double yawRad = Math.toRadians(entity.yBodyRot);
        double cos = Math.cos(yawRad);
        double sin = Math.sin(yawRad);

        // Hand position adjusts with arm angle (pitch-driven)
        float pitchRad = (float) Math.toRadians(entity.getXRot());
        double armAngle = -1.2 + pitchRad;
        double armLen = 0.75;
        double deltaY = -armLen * (Math.cos(armAngle) - Math.cos(-1.2));
        double deltaFwd = -armLen * (Math.sin(armAngle) - Math.sin(-1.2));
        double fwd = 0.6 + deltaFwd;
        double oy = entity.getY() + 1.5 + deltaY;

        // Origin: right hand position
        double ox = entity.getX() - 0.65 * cos - fwd * sin;
        double oz = entity.getZ() - 0.65 * sin + fwd * cos;

        // Aim at debug target (diamond block), or fallback to facing direction
        Optional<BlockPos> debugTarget = entity.getDebugTarget();
        double dx, dy, dz, range;
        if (debugTarget.isPresent()) {
            BlockPos bp = debugTarget.get();
            double tx = bp.getX() + 0.5;
            double ty = bp.getY() + 0.5;
            double tz = bp.getZ() + 0.5;
            dx = tx - ox;
            dy = ty - oy;
            dz = tz - oz;
            double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            dx /= len;
            dy /= len;
            dz /= len;
            range = len; // stop at target
        } else {
            dx = -sin;
            dy = 0;
            dz = cos;
            range = RAY_RANGE;
        }

        for (double d = 0.8; d <= range; d += RAY_STEP) {
            CastBoltParticle.spawn(level,
                    ox + dx * d, oy + dy * d, oz + dz * d,
                    color[0], color[1], color[2]);
        }
    }
}
