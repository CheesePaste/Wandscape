package com.wsteam.wandscape.shared.event;

import java.util.UUID;

import net.neoforged.bus.api.Event;

/**
 * Fired after a colony's daily settlement completes.
 * Acts as the daily boundary trigger for periodic systems (shop restock, stats snapshots).
 */
public class DailySettlementEvent extends Event {
    private final SettlementReport report;

    public DailySettlementEvent(SettlementReport report) {
        this.report = report;
    }

    public SettlementReport getReport() { return report; }

    public record SettlementReport(
            UUID colonyId,
            long day
    ) {}
}
