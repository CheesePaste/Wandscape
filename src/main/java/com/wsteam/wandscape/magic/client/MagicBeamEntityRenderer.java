package com.wsteam.wandscape.magic.client;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.wsteam.wandscape.magic.entity.MagicBeamEntity;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * 信标光束渲染：复用原版 {@link BeaconRenderer#renderBeaconBeam}（原版 beam shader + 逐顶点染色）。
 * 把局部 +Y 轴旋转到「源点→目标」方向，再按原版参数画高度=距离的竖直束体，得到指向目标的彩色光束。
 * 不用自定义渲染，避免光影包下异常。
 */
public class MagicBeamEntityRenderer extends EntityRenderer<MagicBeamEntity> {

    public MagicBeamEntityRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(MagicBeamEntity entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        Vec3 src = entity.getPosition(partialTick);
        BlockPos tgtPos = entity.getTarget().orElse(null);
        if (tgtPos == null) return;
        Vec3 tgt = tgtPos.getCenter();
        Vec3 dir = tgt.subtract(src);
        double dist = dir.length();
        if (dist < 0.1) return;
        Vec3 ndir = dir.normalize();
        int height = Math.max(1, (int) Math.round(dist));

        poseStack.pushPose();
        poseStack.translate(src.x, src.y, src.z);
        poseStack.mulPose(rotationYTo(ndir));
        // 抵消 renderBeaconBeam 内部 translate(0.5, 0, 0.5)，使光束中心对准源点
        poseStack.translate(-0.5, 0, -0.5);
        BeaconRenderer.renderBeaconBeam(poseStack, buffer, BeaconRenderer.BEAM_LOCATION,
                partialTick, 1.0f, entity.level().getGameTime(), 0, height,
                entity.getBeamColor(), 0.2f, 0.25f);
        poseStack.popPose();
    }

    /** 构造把 (0,1,0) 旋转到 dir 的四元数（最短弧），dir 平行 +Y/-Y 时特判。 */
    private static Quaternionf rotationYTo(Vec3 dir) {
        double d = Mth.clamp(dir.dot(new Vec3(0, 1, 0)), -1.0, 1.0);
        if (Math.abs(1.0 - d) < 1e-4) return new Quaternionf();
        if (Math.abs(-1.0 - d) < 1e-4) {
            return new Quaternionf().rotationAxis((float) Math.PI, new Vector3f(1, 0, 0));
        }
        Vec3 axis = new Vec3(0, 1, 0).cross(dir).normalize();
        double angle = Math.acos(d);
        return new Quaternionf().rotationAxis((float) angle,
                new Vector3f((float) axis.x, (float) axis.y, (float) axis.z));
    }

    @Override
    public ResourceLocation getTextureLocation(MagicBeamEntity entity) {
        return BeaconRenderer.BEAM_LOCATION;
    }
}
