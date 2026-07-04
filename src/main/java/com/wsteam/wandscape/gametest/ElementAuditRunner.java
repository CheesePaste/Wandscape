package com.wsteam.wandscape.gametest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;

import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.element.internal.ElementAuditor;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/**
 * Runs the element coverage audit when the game test server starts
 * (detected via {@code wandscape.runAudit} system property), saves the
 * report to {@code build/reports/element_audit.txt}, then shuts down.
 *
 * <p>Run with: {@code ./gradlew runGameTestServer}
 * Results in: {@code build/reports/element_audit.txt}
 */
@EventBusSubscriber(modid = Wandscape.MODID)
public class ElementAuditRunner {

    private static final String SEEDS_RESOURCE = "data/wandscape/element_seeds.json";

    // Resolve report path relative to project root (game test server CWD is project root)
    private static final Path REPORT_PATH = Paths.get("build", "reports", "element_audit.txt")
            .toAbsolutePath().normalize();

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        // Only run when explicitly enabled (gameTestServer run config sets this)
        if (!Boolean.getBoolean("wandscape.runAudit")) return;

        try {
            // 1. Load seeds
            String seedJson = readResource(SEEDS_RESOURCE);
            Set<String> seedIds = ElementAuditor.parseSeedIds(seedJson);

            // 2. Collect mapped IDs from loaded element_mappings
            Set<String> mappedIds = new HashSet<>();
            for (var config : Wandscape.ELEMENT_MAPPING_LOADER.getAllConfigs()) {
                if (config.blockId() != null) mappedIds.add(config.blockId());
                if (config.itemId() != null) mappedIds.add(config.itemId());
            }

            // 3. Run audit
            var report = ElementAuditor.audit(seedIds, mappedIds);
            String output = report.toFormattedString();

            // 4. Save to file
            Files.createDirectories(REPORT_PATH.getParent());
            Files.writeString(REPORT_PATH, output);

            System.out.println("=== Element Audit Report ===");
            System.out.println(output);
            System.out.println("Report saved to: " + REPORT_PATH.toAbsolutePath().normalize());
        } catch (Exception e) {
            System.err.println("Element audit failed: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // Shut down so the task completes
            event.getServer().halt(false);
        }
    }

    private static String readResource(String path) throws IOException {
        ClassLoader cl = ElementAuditRunner.class.getClassLoader();
        try (InputStream is = cl.getResourceAsStream(path)) {
            if (is == null) throw new IOException("Resource not found: " + path);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
