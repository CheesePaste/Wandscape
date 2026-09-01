package com.wsteam.wandscape.content.tourist.client;
import com.wsteam.wandscape.content.task.ecs.World;

import com.mojang.blaze3d.vertex.PoseStack;
import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.foundation.ui.bubble.AmbientTextPools;
import com.wsteam.wandscape.foundation.ui.bubble.SpeechBubbleRenderer;
import com.wsteam.wandscape.content.tourist.data.Activity;
import com.wsteam.wandscape.content.tourist.entity.TouristEntity;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
public class TouristRenderer extends HumanoidMobRenderer<TouristEntity, TouristHumanoidModel> {

    private static final ResourceLocation[] TOURIST_TEXTURES = detectTextures(
            "textures/entity/tourist");
    private static final ResourceLocation[] WIZARD_TEXTURES = detectTextures(
            "textures/entity/wizard");

    private static ResourceLocation[] detectTextures(String subPath) {
        try {
            Path dir = ModList.get().getModFileById(Wandscape.MODID).getFile()
                    .findResource("assets", "wandscape", subPath);
            try (Stream<Path> files = Files.list(dir)) {
                List<String> names = files
                        .map(p -> p.getFileName().toString())
                        .filter(n -> n.endsWith(".png"))
                        .sorted()
                        .toList();
                if (!names.isEmpty()) {
                    ResourceLocation[] arr = new ResourceLocation[names.size()];
                    for (int i = 0; i < names.size(); i++) {
                        arr[i] = ResourceLocation.fromNamespaceAndPath("wandscape",
                                subPath + "/" + names.get(i));
                    }
                    return arr;
                }
            }
        } catch (IOException | RuntimeException ignored) {}
        return new ResourceLocation[] {
            ResourceLocation.fromNamespaceAndPath("wandscape", subPath + "/fallback.png")
        };
    }

    public TouristRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new TouristHumanoidModel(ctx.bakeLayer(ModelLayers.PLAYER)), 0.5f);
        // 让手持物品（EAT 时手里的食物）渲染在手上
        this.addLayer(new ItemInHandLayer<>(this, ctx.getItemInHandRenderer()));
    }

    @Override
    public void render(TouristEntity entity, float entityYaw, float partialTicks,
                       PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight);
        spawnActivityParticles(entity);
        // 预览假人：只显示动作姿态/粒子，不弹闲聊气泡
        if (!entity.isPreview()) {
            SpeechBubbleRenderer.renderBubble(entity, poseStack, buffer, packedLight,
                    AmbientTextPools::getTouristText);
        }
    }

    /** 按 activity 发射装饰粒子（泡澡蒸汽/冥想魔法/取现金币）；节流，未知活动无粒子。 */
    private void spawnActivityParticles(TouristEntity entity) {
        Activity activity = entity.getCurrentActivity();
        if (activity == null) return;
        if (!(entity.level() instanceof ClientLevel cl)) return;

        if (activity == Activity.EAT) {
            spawnEatParticles(entity, cl);
            return;
        }
        if (entity.tickCount % 8 != 0) return;
        var spec = ActivityVisuals.safeFor(activity).particles();
        if (spec == null) return;
        double x = entity.getX();
        double y = entity.getY() + 1.4;
        double z = entity.getZ();
        for (int i = 0; i < spec.count(); i++) {
            cl.addParticle(spec.type(),
                    x + (entity.getRandom().nextDouble() - 0.5) * spec.spread(),
                    y + (entity.getRandom().nextDouble() - 0.5) * spec.spread(),
                    z + (entity.getRandom().nextDouble() - 0.5) * spec.spread(),
                    0, 0.02, 0);
        }
    }

    /** 进食粒子：像玩家吃东西一样，从嘴边冒出食物的碎屑（ItemParticleOption，用主手持物）。 */
    private void spawnEatParticles(TouristEntity entity, ClientLevel cl) {
        if (entity.tickCount % 6 != 0) return;
        ItemStack food = entity.getMainHandItem();
        if (food.isEmpty()) food = new ItemStack(Items.BREAD);

        float yaw = entity.getYRot() * (float) (Math.PI / 180.0);
        float pitch = entity.getXRot() * (float) (Math.PI / 180.0);
        for (int i = 0; i < 2; i++) {
            Vec3 speed = new Vec3((entity.getRandom().nextFloat() - 0.5F) * 0.1D,
                    entity.getRandom().nextFloat() * 0.1D + 0.1D, 0.0D);
            speed = speed.xRot(-pitch).yRot(-yaw);
            Vec3 mouth = new Vec3((entity.getRandom().nextFloat() - 0.5F) * 0.3D,
                    -entity.getRandom().nextFloat() * 0.4D - 0.2D, 0.6D);
            mouth = mouth.xRot(-pitch).yRot(-yaw)
                    .add(entity.getX(), entity.getEyeY() - 0.1D, entity.getZ());
            cl.addParticle(new ItemParticleOption(ParticleTypes.ITEM, food),
                    mouth.x, mouth.y, mouth.z, speed.x, speed.y + 0.05D, speed.z);
        }
    }

    @Override
    public ResourceLocation getTextureLocation(TouristEntity entity) {
        int variant = entity.getSkinVariant();
        boolean isMage = entity.isMage();

        if (isMage) {
            if (variant >= 0 && variant < WIZARD_TEXTURES.length) {
                return WIZARD_TEXTURES[variant];
            }
            return WIZARD_TEXTURES[0];
        }

        if (variant >= 0 && variant < TOURIST_TEXTURES.length) {
            return TOURIST_TEXTURES[variant];
        }
        return TOURIST_TEXTURES[0];
    }
}
