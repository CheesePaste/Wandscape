package com.wsteam.wandscape.shared.api;

import java.util.UUID;

import com.wsteam.wandscape.shared.data.ColonyMetricsSnapshot;

/**
 * Read-only facade that aggregates all colony-level metrics from
 * specialized APIs into a single {@link ColonyMetricsSnapshot}.
 *
 * <p>Retrieve via {@link com.wsteam.wandscape.shared.registry.WandscapeApis#getColonyMetricsApi()}.
 */
public interface ColonyMetricsApi {

    ColonyMetricsSnapshot getSnapshot(UUID colonyId);

    default ColonyMetricsSnapshot getSnapshotSafe(UUID colonyId) {
        try {
            ColonyMetricsSnapshot snap = getSnapshot(colonyId);
            return snap != null ? snap : ColonyMetricsSnapshot.EMPTY;
        } catch (Exception e) {
            return ColonyMetricsSnapshot.EMPTY;
        }
    }
}
