package com.wsteam.wandscape.npc.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import com.mojang.blaze3d.vertex.PoseStack;
import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.npc.entity.WandscapeNpc;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.fml.ModList;
import org.joml.Matrix4f;

public class WandscapeNpcRenderer extends HumanoidMobRenderer<WandscapeNpc, HumanoidModel<WandscapeNpc>> {

    public static final ModelLayerLocation WIZARD_HAT_LAYER =
            new ModelLayerLocation(ResourceLocation.fromNamespaceAndPath("wandscape", "wandscape_npc"), "wizard_hat");

    private static final ResourceLocation[] TEXTURES = detectTextures();

    private static ResourceLocation[] detectTextures() {
        try {
            Path dir = ModList.get().getModFileById(Wandscape.MODID).getFile()
                    .findResource("assets", "wandscape", "textures", "entity", "wizard");
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
                                "textures/entity/wizard/" + names.get(i));
                    }
                    return arr;
                }
            }
        } catch (IOException | RuntimeException ignored) {}
        return new ResourceLocation[] {
            ResourceLocation.fromNamespaceAndPath("wandscape", "textures/entity/wizard/wizard01.png")
        };
    }
    private static final float[] DEFAULT_CAST_COLOR = {1.0f, 1.0f, 1.0f};
    private static final double RAY_RANGE = 5.0;
    private static final double RAY_STEP = 0.4;

    public WandscapeNpcRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new WandscapeNpcModel(ctx.bakeLayer(ModelLayers.PLAYER)), 0.5f);
        this.addLayer(new WizardHatLayer(this,
                new WizardHatModel(ctx.bakeLayer(WIZARD_HAT_LAYER))));
    }

    @Override
    public ResourceLocation getTextureLocation(WandscapeNpc entity) {
        int variant = entity.getSkinVariant();
        if (variant < 0 || variant >= TEXTURES.length) {
            return TEXTURES[0];
        }
        return TEXTURES[variant];
    }

    private static final float STATUS_SCALE = 0.025F;
    private static final float STATUS_Y_OFFSET = 0.45F; // slightly below vanilla nametag

    @Override
    public void render(WandscapeNpc entity, float entityYaw, float partialTicks,
                       PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        if (entity.isCasting()) {
            // Throttle to 1×/tick — render() may fire multiple times per frame
            // (opaque pass, translucent pass, outline pass, etc.)
            if (entity.tickCount != entity.lastParticleTick) {
                entity.lastParticleTick = entity.tickCount;
                String kind = entity.getOpKind();
                if (kind != null && kind.startsWith("ritual:")) {
                    spawnRitualCircle(entity, kind.substring(7));
                } else {
                    spawnCastRay(entity);
                }
            }
        }
        super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight);

        // Render status text above NPC head
        String status = entity.getStatusText();
        if (!status.isEmpty()) {
            renderStatusText(entity, status, poseStack, buffer, packedLight);
        }
    }

    private void renderStatusText(WandscapeNpc entity, String text, PoseStack poseStack,
                                  MultiBufferSource buffer, int packedLight) {
        Component displayName = Component.literal("§7" + text); // gray italics-like
        Font font = this.getFont();
        double dist = this.entityRenderDispatcher.distanceToSqr(entity);
        if (dist > 4096.0) return; // >64 blocks, don't render

        poseStack.pushPose();
        poseStack.translate(0, entity.getBbHeight() + STATUS_Y_OFFSET, 0);
        poseStack.mulPose(this.entityRenderDispatcher.cameraOrientation());
        poseStack.scale(STATUS_SCALE, -STATUS_SCALE, STATUS_SCALE);
        Matrix4f matrix4f = poseStack.last().pose();

        float x = (float)(-font.width(displayName) / 2);
        int bgAlpha = (int)(Minecraft.getInstance().options.getBackgroundOpacity(0.25F) * 255.0F) << 24;
        font.drawInBatch(displayName, x, 0, 0xDDDDDD, false, matrix4f, buffer,
                Font.DisplayMode.SEE_THROUGH, bgAlpha, packedLight);
        font.drawInBatch(displayName, x, 0, -1, false, matrix4f, buffer,
                Font.DisplayMode.NORMAL, 0, packedLight);
        poseStack.popPose();
    }

    // ── Magic circle (ritual ops) ──

    private static final int CIRCLE_PARTICLES = 16;
    private static final double CIRCLE_RADIUS = 0.8;

    /**
     * Spawn a rotating magic circle at the target position.
     * Different ritual types can have different visual styles.
     *
     * @param entity   the casting NPC
     * @param ritualId the ritual type (e.g. "self_teleport", "warding", "portal_gate")
     */
    private void spawnRitualCircle(WandscapeNpc entity, String ritualId) {
        if (!entity.level().isClientSide) return;

        ClientLevel level = (ClientLevel) entity.level();
        Optional<BlockPos> target = entity.getDebugTarget();
        if (target.isEmpty()) return;

        BlockPos bp = target.get();
        double cx = bp.getX() + 0.5;
        double cz = bp.getZ() + 0.5;
        long time = System.currentTimeMillis();

        // Base rotation speed (varies slightly per ritual for visual variety)
        double speed = switch (ritualId) {
            case "portal_gate" -> 2.5;
            case "rain_call", "clear_weather" -> 1.0;
            default -> 1.8;
        };
        double baseAngle = (time % 4000) / 4000.0 * Math.PI * 2 * speed;

        for (int ring = 0; ring < 3; ring++) {
            double y = bp.getY() + 0.15 + ring * 0.35;
            double radius = CIRCLE_RADIUS + ring * 0.05;
            double offset = ring * 0.4; // stagger each ring

            for (int i = 0; i < CIRCLE_PARTICLES; i++) {
                double angle = baseAngle + offset + (i / (double) CIRCLE_PARTICLES) * Math.PI * 2;
                double px = cx + Math.cos(angle) * radius;
                double pz = cz + Math.sin(angle) * radius;
                level.addParticle(ParticleTypes.ENCHANT, px, y, pz, 0, 0.02, 0);
            }
        }
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
