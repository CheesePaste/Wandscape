package com.wsteam.wandscape.building;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.wsteam.wandscape.content.building.data.BlockOffset;
import com.wsteam.wandscape.content.building.data.BuildingConfig;
import com.wsteam.wandscape.shared.data.WonderEffect;

import org.junit.jupiter.api.Test;

/**
 * Regression guard: every shipping building JSON must parse into a BuildingConfig
 * with palette + block_indices (legacy block_mapping is rejected). Runs over the
 * real resources so a data regression is caught in CI, not in-game.
 */
class AllBuildingConfigsLoadTest {

    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(BlockOffset.class, new BlockOffset.Deserializer())
            .registerTypeAdapter(BuildingConfig.class, new BuildingConfig.Deserializer())
            .registerTypeAdapter(WonderEffect.class, new WonderEffect.Deserializer())
            .create();

    @Test
    void allShippedBuildingConfigsParse() throws Exception {
        URL u = getClass().getResource("/data/wandscape/buildings/sea_store.json");
        assertNotNull(u, "building resources must be on the test classpath");
        Path dir = Paths.get(u.toURI()).getParent();
        List<Path> files;
        try (var stream = Files.walk(dir)) {
            files = stream.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
        assertTrue(files.size() >= 10, "expected many building configs, got " + files.size());

        int totalBlocks = 0;
        for (Path f : files) {
            String json = Files.readString(f);
            BuildingConfig cfg = GSON.fromJson(json, BuildingConfig.class);
            assertNotNull(cfg, "parse failed for " + f.getFileName());
            assertFalse(cfg.id().isEmpty(), "missing id in " + f.getFileName());
            assertEquals(cfg.pattern().size(), cfg.blockIndices().size(),
                    "block_indices/pattern misaligned in " + f.getFileName());
            totalBlocks += cfg.pattern().size();
        }
        assertTrue(totalBlocks > 1000, "sanity: summed blocks across configs");
    }
}
