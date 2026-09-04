package com.wsteam.wandscape.content.building.projection.client;

/**
 * Formerly rendered the building info top bar & action buttons in the V-panel HUD.
 * Building info and repair/demolish actions have been moved inside individual building
 * UIs (via {@link com.wsteam.wandscape.foundation.ui.component.MedievalScreen}).
 *
 * @deprecated Replaced by MedievalScreen header info & footer action buttons.
 */
@Deprecated
public final class BuildingDebugOverlay {
    private BuildingDebugOverlay() {}

    public static void register() {
        // No-op: HUD top bar removed in favor of in-screen UI.
    }
}
