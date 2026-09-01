package com.wsteam.wandscape.road.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a structure template (blueprint) for array generation along a spline.
 * Uses integer coordinates for local positions.
 */
public class RoadTemplate {
    private String id;
    private final List<RoadTemplateBlock> blocks = new ArrayList<>();

    public RoadTemplate(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public List<RoadTemplateBlock> getBlocks() {
        return blocks;
    }

    public void addBlock(int x, int y, int z, String blockState) {
        blocks.add(new RoadTemplateBlock(x, y, z, blockState));
    }

    public record RoadTemplateBlock(int x, int y, int z, String blockState) {}
}
