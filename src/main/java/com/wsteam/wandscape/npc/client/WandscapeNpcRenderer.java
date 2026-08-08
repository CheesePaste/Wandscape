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
import com.wsteam.wandscape.shared.client.bubble.AmbientTextPools;
import com.wsteam.wandscape.shared.client.bubble.SpeechBubbleRenderer;
import com.wsteam.wandscape.shared.ui.I18n;

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
import net.minecraft.world.phys.Vec3;
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
        // Speech bubble added inline in render() instead of as RenderLayer
    }

    @Override
    public ResourceLocation getTextureLocation(WandscapeNpc entity) {
        int variant = entity.getSkinVariant();
        if (variant < 0 || variant >= TEXTURES.length) {
            return TEXTURES[0];
        }
        return TEXTURES[variant];
    }

    private static final float NAME_SCALE = 0.025F;
    private static final float NAME_Y_OFFSET = 0.45F; // name right above the head
    private static final float STATUS_Y_OFFSET = 0.70F; // status above the name

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
                } else if (entity.getDebugTarget().isPresent()) {
                    // Only spawn cast ray when there's a target — avoid particles
                    // during movement when the NPC is active but has no op target.
                    spawnCastRay(entity);
                }
            }
        }
        super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight);

        SpeechBubbleRenderer.renderBubble(entity, poseStack, buffer, packedLight,
                AmbientTextPools::getNpcText);

        // Name above the head, status above the name
        renderNamePlate(entity, poseStack, buffer, packedLight);
    }

    /** Suppress the vanilla nametag — the name is drawn by {@link #renderNamePlate}. */
    @Override
    public boolean shouldShowName(WandscapeNpc entity) {
        return false;
    }

    /** Render the mage's name (white) with its status (gray) above it. */
    private void renderNamePlate(WandscapeNpc entity, PoseStack poseStack,
                                 MultiBufferSource buffer, int packedLight) {
        double dist = this.entityRenderDispatcher.distanceToSqr(entity);
        if (dist > 4096.0) return; // >64 blocks, don't render

        Font font = this.getFont();
        int bgAlpha = (int)(Minecraft.getInstance().options.getBackgroundOpacity(0.25F) * 255.0F) << 24;

        // Status above the name (gray)
        String statusKey = entity.getStatusText();
        if (!statusKey.isEmpty()) {
            Component status = I18n.name("npc.wandscape.state." + statusKey, WandscapeNpc.statusFallback(statusKey))
                    .copy().withStyle(style -> style.withColor(0xAAAAAA));
            poseStack.pushPose();
            poseStack.translate(0, entity.getBbHeight() + STATUS_Y_OFFSET, 0);
            poseStack.mulPose(this.entityRenderDispatcher.cameraOrientation());
            poseStack.scale(NAME_SCALE, -NAME_SCALE, NAME_SCALE);
            Matrix4f m = poseStack.last().pose();
            float sx = -font.width(status) / 2f;
            font.drawInBatch(status, sx, 0, 0xAAAAAA, false, m, buffer,
                    Font.DisplayMode.SEE_THROUGH, bgAlpha, packedLight);
            font.drawInBatch(status, sx, 0, -1, false, m, buffer,
                    Font.DisplayMode.NORMAL, 0, packedLight);
            poseStack.popPose();
        }

        // Name below the status (right above the head, white)
        Component name = Component.literal(entity.getNpcName());
        poseStack.pushPose();
        poseStack.translate(0, entity.getBbHeight() + NAME_Y_OFFSET, 0);
        poseStack.mulPose(this.entityRenderDispatcher.cameraOrientation());
        poseStack.scale(NAME_SCALE, -NAME_SCALE, NAME_SCALE);
        Matrix4f m2 = poseStack.last().pose();
        float nx = -font.width(name) / 2f;
        font.drawInBatch(name, nx, 0, 0xFFFFFF, false, m2, buffer,
                Font.DisplayMode.SEE_THROUGH, bgAlpha, packedLight);
        font.drawInBatch(name, nx, 0, -1, false, m2, buffer,
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

        // Hand position from shared geometry（与施法/光束同一来源，含手臂角度）
        Vec3 hand = entity.getStaffPosition();
        double ox = hand.x;
        double oy = hand.y;
        double oz = hand.z;

        // 朝向：有 debug target 则从右手指向目标，否则水平正前
        Optional<BlockPos> debugTarget = entity.getDebugTarget();
        Vec3 dir;
        double range;
        if (debugTarget.isPresent()) {
            BlockPos bp = debugTarget.get();
            Vec3 target = new Vec3(bp.getX() + 0.5, bp.getY() + 0.5, bp.getZ() + 0.5);
            dir = target.subtract(hand).normalize();
            range = target.distanceTo(hand);
        } else {
            dir = entity.getFacingDirection();
            range = RAY_RANGE;
        }

        for (double d = 0.8; d <= range; d += RAY_STEP) {
            CastBoltParticle.spawn(level,
                    ox + dir.x * d, oy + dir.y * d, oz + dir.z * d,
                    color[0], color[1], color[2]);
        }
    }
}
