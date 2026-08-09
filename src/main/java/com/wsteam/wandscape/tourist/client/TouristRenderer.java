package com.wsteam.wandscape.tourist.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import com.mojang.blaze3d.vertex.PoseStack;
import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.tourist.entity.TouristEntity;

import com.wsteam.wandscape.shared.client.bubble.AmbientTextPools;
import com.wsteam.wandscape.shared.client.bubble.SpeechBubbleRenderer;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;
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
    }

    @Override
    public void render(TouristEntity entity, float entityYaw, float partialTicks,
                       PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight);
        spawnActivityParticles(entity);
        SpeechBubbleRenderer.renderBubble(entity, poseStack, buffer, packedLight,
                AmbientTextPools::getTouristText);
    }

    /** 按 activity 发射装饰粒子（泡澡蒸汽/冥想魔法/取现金币）；节流，未知活动无粒子。 */
    private void spawnActivityParticles(TouristEntity entity) {
        if (entity.getCurrentActivity() == null) return;
        if (entity.tickCount % 8 != 0) return;
        var spec = ActivityVisuals.safeFor(entity.getCurrentActivity()).particles();
        if (spec == null || !(entity.level() instanceof ClientLevel cl)) return;
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
