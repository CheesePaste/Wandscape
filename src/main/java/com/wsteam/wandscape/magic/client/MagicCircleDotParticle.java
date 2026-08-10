package com.wsteam.wandscape.magic.client;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.core.particles.SimpleParticleType;

/**
 * 魔法阵可染色点粒子（自定义 glow/ember + glyph 放大点，v1 用同一类）。
 * 复用原版 glow 贴图 + 元素 color 染色；支持彗星头尾缩放（glyph）与拖尾淡出（continuous）。
 * quadSize 是半宽，渲染宽 = 2×quadSize。
 */
public class MagicCircleDotParticle extends TextureSheetParticle {

    private static SpriteSet cachedSprite;

    private final SpriteSet sprites;
    private final float startSize;
    private final float endSize;
    private final float baseAlpha;
    private final boolean fadeOut;
    /** 运动粒子（爆花/光柱用）：tick 时保留速度，不自清。 */
    private final boolean moving;

    protected MagicCircleDotParticle(ClientLevel level, double x, double y, double z,
                                     float r, float g, float b,
                                     float startSize, float endSize,
                                     float baseAlpha, boolean fadeOut,
                                     int lifetime, SpriteSet sprites) {
        this(level, x, y, z, r, g, b, startSize, endSize, baseAlpha, fadeOut,
                lifetime, sprites, 0, 0, 0, false);
    }

    protected MagicCircleDotParticle(ClientLevel level, double x, double y, double z,
                                     float r, float g, float b,
                                     float startSize, float endSize,
                                     float baseAlpha, boolean fadeOut,
                                     int lifetime, SpriteSet sprites,
                                     double vx, double vy, double vz, boolean moving) {
        super(level, x, y, z, 0, 0, 0);
        this.sprites = sprites;
        this.startSize = startSize;
        this.endSize = endSize;
        this.baseAlpha = baseAlpha;
        this.fadeOut = fadeOut;
        this.moving = moving;
        this.lifetime = Math.max(1, lifetime);
        this.quadSize = startSize;
        this.hasPhysics = false;
        this.gravity = 0;
        this.friction = 1.0f;
        this.xd = vx;
        this.yd = vy;
        this.zd = vz;
        this.setColor(r, g, b);
        this.setAlpha(baseAlpha);
        this.pickSprite(sprites);
    }

    @Override
    public void tick() {
        if (!moving) {
            this.xd = 0;
            this.yd = 0;
            this.zd = 0;
        }
        super.tick();
        float f = Math.min(1.0f, (float) this.age / this.lifetime);
        this.quadSize = startSize + (endSize - startSize) * f;
        float a = baseAlpha;
        if (fadeOut) a *= (1.0f - f);
        this.setAlpha(a);
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
    }

    /** 全亮，匹配 end_rod 的光照表现。 */
    @Override
    public int getLightColor(float partialTick) {
        return 15728880;
    }

    /**
     * 撒一个点粒子。size 为 quadSize（半宽）；fadeOut=true 时随寿命线性淡出（拖尾）。
     */
    public static void spawn(ClientLevel level, double x, double y, double z,
                             float r, float g, float b,
                             float startSize, float endSize,
                             float baseAlpha, boolean fadeOut, int lifetime) {
        if (level == null) return;
        if (cachedSprite == null) {
            level.addParticle(com.wsteam.wandscape.Wandscape.MAGIC_GLOW.get(), x, y, z, 0, 0, 0);
            return;
        }
        var p = new MagicCircleDotParticle(level, x, y, z, r, g, b,
                startSize, endSize, baseAlpha, fadeOut, lifetime, cachedSprite);
        Minecraft.getInstance().particleEngine.add(p);
    }

    /**
     * 撒一个运动粒子（爆花/光柱用）：带速度，随寿命淡出，可染色。
     */
    public static void spawnMoving(ClientLevel level, double x, double y, double z,
                                   double vx, double vy, double vz,
                                   float r, float g, float b,
                                   float size, float alpha, int lifetime) {
        if (cachedSprite == null) return;
        var p = new MagicCircleDotParticle(level, x, y, z, r, g, b,
                size, size, alpha, true, lifetime, cachedSprite,
                vx, vy, vz, true);
        Minecraft.getInstance().particleEngine.add(p);
    }

    public static class Provider implements ParticleProvider<SimpleParticleType> {
        public Provider(SpriteSet sprite) {
            MagicCircleDotParticle.cachedSprite = sprite;
        }

        @Nullable
        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level,
                                       double x, double y, double z,
                                       double vx, double vy, double vz) {
            return new MagicCircleDotParticle(level, x, y, z, 1f, 1f, 1f,
                    0.12f, 0.12f, 1f, false, 8, cachedSprite);
        }
    }
}
