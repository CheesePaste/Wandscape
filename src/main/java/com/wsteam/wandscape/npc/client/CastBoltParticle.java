package com.wsteam.wandscape.npc.client;

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
 * Stationary bright star particle — marks the wand's beam path.
 * Brightness matches end_rod via getLightColor() = max light.
 */
public class CastBoltParticle extends TextureSheetParticle {

    private static SpriteSet cachedSprite;

    private final SpriteSet sprites;
    private final float startSize;

    protected CastBoltParticle(ClientLevel level, double x, double y, double z,
                               float r, float g, float b, SpriteSet sprites) {
        super(level, x, y, z, 0, 0, 0);
        this.sprites = sprites;
        this.lifetime = 10 + random.nextInt(6);
        this.startSize = 0.08f;
        this.quadSize = startSize;
        this.hasPhysics = false;
        this.gravity = 0;
        this.friction = 1.0f;
        this.xd = 0;
        this.yd = 0;
        this.zd = 0;
        this.setColor(r, g, b);
        this.setAlpha(1.0f);
        this.setSpriteFromAge(sprites);
    }

    @Override
    public void tick() {
        this.xd = 0;
        this.yd = 0;
        this.zd = 0;
        super.tick();
        this.setSpriteFromAge(sprites);
        // Shrink to nothing in last 20% of life
        float remaining = 1.0f - (float) this.age / this.lifetime;
        if (remaining < 0.2f) {
            this.quadSize = startSize * (remaining / 0.2f);
        }
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
    }

    /** Full brightness regardless of world lighting, matching end_rod. */
    @Override
    public int getLightColor(float partialTick) {
        return 15728880;
    }

    @Nullable
    public static Particle spawn(ClientLevel level, double x, double y, double z,
                                  float r, float g, float b) {
        if (cachedSprite == null) return null;
        var particle = new CastBoltParticle(level, x, y, z, r, g, b, cachedSprite);
        Minecraft.getInstance().particleEngine.add(particle);
        return particle;
    }

    public static class Provider implements ParticleProvider<SimpleParticleType> {
        public Provider(SpriteSet sprite) {
            CastBoltParticle.cachedSprite = sprite;
        }

        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level,
                                        double x, double y, double z,
                                        double vx, double vy, double vz) {
            return new CastBoltParticle(level, x, y, z, 1f, 1f, 1f, cachedSprite);
        }
    }
}
