package com.wsteam.wandscape.shared.event;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.wsteam.wandscape.shared.data.ElementType;

import net.neoforged.bus.api.Event;

/**
 * Fired after a colony's daily settlement completes.
 * Carries a report of what happened during settlement.
 */
public class DailySettlementEvent extends Event {
    private final SettlementReport report;

    public DailySettlementEvent(SettlementReport report) {
        this.report = report;
    }

    public SettlementReport getReport() { return report; }

    public record SettlementReport(
            UUID colonyId,
            long day,
            Map<ElementType, Long> totalConsumed,
            List<BuildingSettlementResult> buildingResults,
            Map<ElementType, Long> reservesBefore,
            Map<ElementType, Long> reservesAfter
    ) {}

    public record BuildingSettlementResult(
            UUID buildingId,
            String buildingTypeId,
            String category,
            boolean paid,
            boolean wasShutdown,
            boolean wasRestarted
    ) {}
}
