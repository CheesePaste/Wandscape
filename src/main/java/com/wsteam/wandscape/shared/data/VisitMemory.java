package com.wsteam.wandscape.shared.data;

/**
 * A single visit record for a tourist's journey memory.
 * Stores what happened, when, and how the tourist felt.
 *
 * <p>Stored in {@link java.util.List} on {@code TouristEntity} (max 24 entries, FIFO).
 * Not persisted — only lives for the current trip.
 */
public record VisitMemory(
        String buildingTypeId,
        String buildingDisplayName,
        String category,
        long gameTime,
        int satisfactionBefore,
        int satisfactionDelta,
        int energyDelta,
        /** One-line event summary, e.g. "购买了 面包". */
        String whatHappened,
        /** Computed from satisfactionDelta. */
        Emotion emotion
) {

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String buildingTypeId = "";
        private String buildingDisplayName = "";
        private String category = "basic";
        private long gameTime;
        private int satisfactionBefore;
        private int satisfactionDelta;
        private int energyDelta;
        private String whatHappened = "";

        public Builder buildingTypeId(String v) { this.buildingTypeId = v; return this; }
        public Builder buildingDisplayName(String v) { this.buildingDisplayName = v; return this; }
        public Builder category(String v) { this.category = v; return this; }
        public Builder gameTime(long v) { this.gameTime = v; return this; }
        public Builder satisfactionBefore(int v) { this.satisfactionBefore = v; return this; }
        public Builder satisfactionDelta(int v) { this.satisfactionDelta = v; return this; }
        public Builder energyDelta(int v) { this.energyDelta = v; return this; }
        public Builder whatHappened(String v) { this.whatHappened = v; return this; }

        public VisitMemory build() {
            return new VisitMemory(
                    buildingTypeId, buildingDisplayName, category, gameTime,
                    satisfactionBefore, satisfactionDelta, energyDelta,
                    whatHappened, Emotion.fromDelta(satisfactionDelta));
        }
    }
}
