package com.wsteam.wandscape.npc.client;

import com.wsteam.wandscape.npc.entity.WandscapeNpc;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.resources.ResourceLocation;

/**
 * Minimal client renderer for WandscapeNpc.
 * Uses the player model as a fallback — a custom wizard model will replace this
 * in a later stage when art assets are available.
 */
public class WandscapeNpcRenderer extends HumanoidMobRenderer<WandscapeNpc, HumanoidModel<WandscapeNpc>> {

    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath("wandscape", "textures/entity/wizard.png");

    public WandscapeNpcRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new HumanoidModel<>(ctx.bakeLayer(ModelLayers.PLAYER)), 0.5f);
    }

    @Override
    public ResourceLocation getTextureLocation(WandscapeNpc entity) {
        return TEXTURE;
    }
}
