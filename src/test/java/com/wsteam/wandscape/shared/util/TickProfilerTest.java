package com.wsteam.wandscape.shared.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TickProfilerTest {

    @BeforeEach
    void setUp() {
        TickProfiler.INSTANCE.disable();
        TickProfiler.INSTANCE.clear();
    }

    @AfterEach
    void tearDown() {
        TickProfiler.INSTANCE.disable();
        TickProfiler.INSTANCE.clear();
        try {
            Files.deleteIfExists(TickProfiler.INSTANCE.getCsvPath());
        } catch (IOException ignored) {
        }
    }

    @Test
    void testDisabledBehavior() {
        assertFalse(TickProfiler.INSTANCE.isEnabled());
        var span = TickProfiler.INSTANCE.start("test.disabled");
        assertSame(TickProfiler.Span.NOOP, span);
        TickProfiler.INSTANCE.end(span);
        assertEquals(0, TickProfiler.INSTANCE.getRecordedSpanCount());
    }

    @Test
    void testEnabledRecordingAndFlush() throws IOException {
        TickProfiler.INSTANCE.enable();
        assertTrue(TickProfiler.INSTANCE.isEnabled());

        try (var span = TickProfiler.INSTANCE.start("test.span")) {
            // simulate brief work
            assertNotNull(span);
        }

        TickProfiler.INSTANCE.record("test.manual", 500000L, "extra_info");

        assertEquals(2, TickProfiler.INSTANCE.getRecordedSpanCount());

        TickProfiler.INSTANCE.flushTick(100L);

        List<String> lines = Files.readAllLines(TickProfiler.INSTANCE.getCsvPath());
        assertTrue(lines.size() >= 3); // header + 2 rows
        assertEquals("gameTime,label,durationNanos,extra", lines.get(0));
        assertTrue(lines.get(1).startsWith("100,test.span,"));
        assertTrue(lines.get(2).startsWith("100,test.manual,500000,extra_info"));
    }

    @Test
    void testSampledRecording() {
        TickProfiler.INSTANCE.enable();

        for (int i = 0; i < 10; i++) {
            try (var span = TickProfiler.INSTANCE.start("test.sampled", 5)) {
                // sampleRate 5 -> only 2 out of 10 should be recorded
            }
        }

        assertEquals(10, TickProfiler.INSTANCE.sampledCount("test.sampled"));
        assertEquals(2, TickProfiler.INSTANCE.getRecordedSpanCount());
    }
}
