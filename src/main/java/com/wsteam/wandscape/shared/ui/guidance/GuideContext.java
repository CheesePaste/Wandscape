package com.wsteam.wandscape.shared.ui.guidance;

import com.wsteam.wandscape.shared.network.BuildingAreaSyncPacket;

/**
 * Client-visible colony state accessor used by tutorial step completion
 * predicates. {@link #fromBuildingCache()} backs it with the building-area
 * cache; tests supply a fake implementation so step logic is unit-testable.
 */
public interface GuideContext {

    /** True if the colony has any building whose category equals {@code category}. */
    boolean hasCategory(String category);

    /** True if the colony has a building with the given {@code buildingTypeId}. */
    boolean hasType(String buildingTypeId);

    /** Context backed by the client building-area cache. */
    static GuideContext fromBuildingCache() {
        var buildings = BuildingAreaSyncPacket.getCached();
        return new GuideContext() {
            @Override
            public boolean hasCategory(String category) {
                for (var b : buildings) {
                    if (category.equals(b.category())) return true;
                }
                return false;
            }

            @Override
            public boolean hasType(String buildingTypeId) {
                for (var b : buildings) {
                    if (buildingTypeId.equals(b.buildingTypeId())) return true;
                }
                return false;
            }
        };
    }
}
