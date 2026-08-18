package com.wsteam.wandscape.shared.util;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lightweight tick-level profiler for debugging CPU bottlenecks.
 * Writes CSV to {@code logs/wandscape-ticks.csv}.
 *
 * <p>Usage:
 * <pre>{@code
 * try (var span = TickProfiler.INSTANCE.start("label")) {
 *     // hot code
 * }
 * // or:
 * var span = TickProfiler.INSTANCE.start("label");
 * // hot code
 * TickProfiler.INSTANCE.end(span);
 * }</pre>
 *
 * <p>At the end of each tick, call {@code flushTick(gameTime)} to write a row batch.
 *
 * <p>For ultra-high-frequency spans (millions per second), use
 * {@link #start(String, int)} with a sample rate to record only every Nth call.
 */
public final class TickProfiler {
    public static final TickProfiler INSTANCE = new TickProfiler();
    private static final Path CSV_PATH = Paths.get("logs", "wandscape-ticks.csv");

    private final ConcurrentLinkedQueue<Span> spans = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<String, AtomicLong> sampleCounters = new ConcurrentHashMap<>();
    private final AtomicLong recordedSpanCount = new AtomicLong();
    private volatile boolean enabled = false;

    private TickProfiler() {
    }

    public void enable() {
        enabled = true;
        try {
            Files.deleteIfExists(CSV_PATH);
            writeHeader();
        } catch (IOException ignored) {
        }
    }

    public void disable() {
        enabled = false;
        sampleCounters.clear();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        if (enabled) {
            enable();
        } else {
            disable();
        }
    }

    public long getRecordedSpanCount() {
        return recordedSpanCount.get();
    }

    public Path getCsvPath() {
        return CSV_PATH;
    }

    public void clear() {
        spans.clear();
        sampleCounters.clear();
        recordedSpanCount.set(0);
    }

    private void writeHeader() throws IOException {
        ensureDir();
        try (BufferedWriter w = Files.newBufferedWriter(CSV_PATH, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            w.write("gameTime,label,durationNanos,extra");
            w.newLine();
        }
    }

    /** Record every call. Use for normal/low-frequency spans. */
    public Span start(String label) {
        if (!enabled) return Span.NOOP;
        return new Span(label, System.nanoTime(), 0);
    }

    /**
     * Record only every {@code sampleRate}-th call.
     * Sample rate 1 = record every call.
     * Sample rate 1000 = record ~1/1000 calls.
     */
    public Span start(String label, int sampleRate) {
        if (!enabled) return Span.NOOP;
        if (sampleRate <= 1) {
            return new Span(label, System.nanoTime(), 0);
        }
        AtomicLong counter = sampleCounters.computeIfAbsent(label, k -> new AtomicLong());
        long n = counter.incrementAndGet();
        if (n % sampleRate != 0) {
            return new Span(label, 0, sampleRate);
        }
        return new Span(label, System.nanoTime(), 0);
    }

    public void end(Span span) {
        if (span == null || span == Span.NOOP || !enabled) return;
        if (span.sampleRate > 0) {
            // Sampled span that was skipped
            return;
        }
        span.durationNanos = System.nanoTime() - span.startNanos;
        spans.add(span);
        recordedSpanCount.incrementAndGet();
    }

    public void record(String label, long durationNanos, String extra) {
        if (!enabled) return;
        spans.add(new Span(label, 0, durationNanos, extra, 0));
        recordedSpanCount.incrementAndGet();
    }

    public void flushTick(long gameTime) {
        if (!enabled || spans.isEmpty()) return;
        List<Span> batch = new ArrayList<>();
        Span s;
        while ((s = spans.poll()) != null) {
            batch.add(s);
        }
        if (batch.isEmpty()) return;

        try {
            ensureDir();
            try (BufferedWriter w = Files.newBufferedWriter(CSV_PATH, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                for (Span span : batch) {
                    w.write(String.format("%d,%s,%d,%s%n",
                            gameTime,
                            span.label.replace(',', ';'),
                            span.durationNanos,
                            span.extra != null ? span.extra.replace(',', ';') : ""));
                }
            }
        } catch (IOException ignored) {
        }
    }

    public long sampledCount(String label) {
        AtomicLong c = sampleCounters.get(label);
        return c != null ? c.get() : 0;
    }

    private static void ensureDir() throws IOException {
        Path parent = CSV_PATH.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    public static final class Span implements AutoCloseable {
        public static final Span NOOP = new Span("", 0, 0, null, 0);

        final String label;
        final long startNanos;
        long durationNanos;
        final String extra;
        final int sampleRate; // 0=live span, >0=sampled-out (skip in end())

        Span(String label, long startNanos, int sampleRate) {
            this(label, startNanos, 0, null, sampleRate);
        }

        Span(String label, long startNanos, long durationNanos, String extra, int sampleRate) {
            this.label = label;
            this.startNanos = startNanos;
            this.durationNanos = durationNanos;
            this.extra = extra;
            this.sampleRate = sampleRate;
        }

        @Override
        public void close() {
            TickProfiler.INSTANCE.end(this);
        }
    }
}
