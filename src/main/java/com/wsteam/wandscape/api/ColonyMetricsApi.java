package com.wsteam.wandscape.api;

import com.wsteam.wandscape.content.colony.data.ColonyMetricsSnapshot;

import java.util.UUID;

/**
 * Read-only facade that aggregates all colony-level metrics from
 * specialized APIs into a single {@link ColonyMetricsSnapshot}.
 *
 * <p>Retrieve via {@link com.wsteam.wandscape.api.WandscapeApis#getColonyMetricsApi()}.
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
