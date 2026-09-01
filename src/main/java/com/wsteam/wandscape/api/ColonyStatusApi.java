package com.wsteam.wandscape.api;

import com.wsteam.wandscape.content.colony.data.ColonyStatusSnapshot;

import java.util.UUID;

/**
 * Read-only facade that aggregates all colony-level metrics from
 * specialized APIs into a single {@link ColonyStatusSnapshot}.
 *
 * <p>Retrieve via {@link com.wsteam.wandscape.api.WandscapeApis#getColonyStatusApi()}.
 */
public interface ColonyStatusApi {

    ColonyStatusSnapshot getSnapshot(UUID colonyId);

    default ColonyStatusSnapshot getSnapshotSafe(UUID colonyId) {
        try {
            ColonyStatusSnapshot snap = getSnapshot(colonyId);
            return snap != null ? snap : ColonyStatusSnapshot.EMPTY;
        } catch (Exception e) {
            return ColonyStatusSnapshot.EMPTY;
        }
    }
}
