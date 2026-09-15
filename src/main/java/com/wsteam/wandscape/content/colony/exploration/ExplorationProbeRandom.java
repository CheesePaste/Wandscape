package com.wsteam.wandscape.content.colony.exploration;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;

/**
 * A {@link RandomSource} that answers every draw with the midpoint of the range it was asked
 * for. Running a loot table once against this source yields its mathematical expectation
 * instead of a sample — no rolling, no per-draw item construction, no waiting.
 *
 * <p>It works because vanilla's number providers are uniform: {@code UniformGenerator} resolves
 * to {@code Mth.nextInt(random, min, max)} (i.e. {@code nextInt(max - min + 1) + min}) and
 * {@code Mth.nextFloat(random, min, max)} (i.e. {@code nextFloat() * span + min}), whose means
 * are exactly what a constant-half draw returns here. {@code ConstantValue} never draws at all.
 * Integer ranges are truncated, so a count can land up to 0.5 under its true mean.
 *
 * <p>Never use this for anything that actually pays out — it is a measuring instrument, not a
 * random source. {@link #forkPositional()} throws for that reason; the loot path never asks.
 */
final class ExplorationProbeRandom implements RandomSource {

    static final ExplorationProbeRandom INSTANCE = new ExplorationProbeRandom();

    private ExplorationProbeRandom() {}

    @Override
    public RandomSource fork() {
        return this;
    }

    @Override
    public PositionalRandomFactory forkPositional() {
        throw new UnsupportedOperationException("ExplorationProbeRandom has no positional form");
    }

    @Override
    public void setSeed(long seed) {
        // Stateless by design: every draw is the midpoint of its own range.
    }

    @Override
    public int nextInt() {
        return 0;
    }

    @Override
    public int nextInt(int bound) {
        return bound <= 0 ? 0 : (bound - 1) / 2;
    }

    @Override
    public long nextLong() {
        return 0L;
    }

    @Override
    public boolean nextBoolean() {
        return false;
    }

    @Override
    public float nextFloat() {
        return 0.5f;
    }

    @Override
    public double nextDouble() {
        return 0.5;
    }

    @Override
    public double nextGaussian() {
        return 0.0;
    }
}
