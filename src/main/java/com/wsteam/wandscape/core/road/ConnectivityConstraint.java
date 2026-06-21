package com.wsteam.wandscape.core.road;

import java.util.UUID;

/**
 * An MST-derived constraint: two buildings must be connected by road.
 *
 * @param fromBuildingId building UUID at one end
 * @param toBuildingId   building UUID at the other end
 * @param fromAccess     computed access point near from-building
 * @param toAccess       computed access point near to-building
 * @param budget         allowed total template cost (manhattanDist × 1.3)
 */
public record ConnectivityConstraint(
        UUID fromBuildingId,
        UUID toBuildingId,
        XZPoint fromAccess,
        XZPoint toAccess,
        int budget) {
}
