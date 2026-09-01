package com.wsteam.wandscape.stats.internal;

import com.wsteam.wandscape.shared.api.ColonyApi;
import com.wsteam.wandscape.shared.event.ColonyEvaluationChangedEvent;
import com.wsteam.wandscape.shared.event.DailySettlementEvent;
import com.wsteam.wandscape.shared.event.TouristArrivedEvent;
import com.wsteam.wandscape.shared.event.TouristDepartedEvent;
import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.shared.network.PanelStateTracker;
import com.wsteam.wandscape.shared.registry.WandscapeApis;
import com.wsteam.wandscape.stats.data.ColonyDailySnapshot;
import com.wsteam.wandscape.stats.data.ColonyStatsSummary;
import com.wsteam.wandscape.stats.network.StatsSyncPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Collects colony statistics by subscribing to domain events.
 *
 * <p>Maintains intra-day counters per colony (tourist traffic, evaluation
 * values) and records a {@link ColonyDailySnapshot} at each settlement
 * boundary. Pushes summary data to panel clients on each update.
 */
public final class StatisticsCollector {

    private static final String TAG = "StatisticsCollector";

    // Intra-day tracking per colony
    private final Map<UUID, Integer> touristsArrived = new HashMap<>();
    private final Map<UUID, Integer> touristsDeparted = new HashMap<>();
    private final Map<UUID, Integer> touristComfortTotal = new HashMap<>();
    private final Map<UUID, Integer> touristMagicTotal = new HashMap<>();
    private final Map<UUID, Integer> touristWonderTotal = new HashMap<>();
    // Latest evaluation values (carried over between days)
    private final Map<UUID, Integer> comfortMap = new HashMap<>();
    private final Map<UUID, Integer> magicMap = new HashMap<>();
    private final Map<UUID, Integer> wonderMap = new HashMap<>();

    private StatisticsCollector() {}

    public static StatisticsCollector register() {
        StatisticsCollector collector = new StatisticsCollector();
        NeoForge.EVENT_BUS.register(collector);
        Log.info(TAG, "StatisticsCollector registered");
        return collector;
    }

    // ── Event handlers ──

    @SubscribeEvent
    public void onDailySettlement(DailySettlementEvent event) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        ServerLevel level = server.overworld();
        if (level == null) return;

        StatisticsData data = StatisticsData.get(level);
        var report = event.getReport();
        UUID colonyId = report.colonyId();

        // Merge intra-day tourist counts
        int a = touristsArrived.getOrDefault(colonyId, 0);
        int d = touristsDeparted.getOrDefault(colonyId, 0);
        int tc = touristComfortTotal.getOrDefault(colonyId, 0);
        int tm = touristMagicTotal.getOrDefault(colonyId, 0);
        int tw = touristWonderTotal.getOrDefault(colonyId, 0);
        int comfort = comfortMap.getOrDefault(colonyId, 0);
        int magic = magicMap.getOrDefault(colonyId, 0);
        int wonder = wonderMap.getOrDefault(colonyId, 0);

        ColonyDailySnapshot snapshot = new ColonyDailySnapshot(
                report.day(),
                a, d, tc, tm, tw,
                comfort, magic, wonder);

        data.addSnapshot(colonyId, snapshot);

        // Reset intra-day counters (preserve evaluation values)
        touristsArrived.put(colonyId, 0);
        touristsDeparted.put(colonyId, 0);
        touristComfortTotal.put(colonyId, 0);
        touristMagicTotal.put(colonyId, 0);
        touristWonderTotal.put(colonyId, 0);

        // Push updated stats to all panel-open players in this colony
        pushStatsToPlayers(server, colonyId, data);
    }

    @SubscribeEvent
    public void onTouristArrived(TouristArrivedEvent event) {
        UUID colonyId = event.getColonyId();
        touristsArrived.merge(colonyId, 1, Integer::sum);
    }

    @SubscribeEvent
    public void onTouristDeparted(TouristDepartedEvent event) {
        UUID colonyId = event.getColonyId();
        touristsDeparted.merge(colonyId, 1, Integer::sum);
        var fill = event.getFill();
        if (fill != null) {
            touristComfortTotal.merge(colonyId, fill.comfort(), Integer::sum);
            touristMagicTotal.merge(colonyId, fill.magic(), Integer::sum);
            touristWonderTotal.merge(colonyId, fill.wonder(), Integer::sum);
        }
    }

    @SubscribeEvent
    public void onColonyEvaluationChanged(ColonyEvaluationChangedEvent event) {
        UUID colonyId = event.getColonyId();
        comfortMap.put(colonyId, event.getNewComfort());
        magicMap.put(colonyId, event.getNewMagic());
        wonderMap.put(colonyId, event.getNewWonder());

        // Piggy-back stats push on evaluation changes (covers panel-open initial sync)
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        ServerLevel level = server.overworld();
        if (level == null) return;
        StatisticsData data = StatisticsData.get(level);
        pushStatsToPlayers(server, colonyId, data);
    }

    // ── Push helpers ──

    private static void pushStatsToPlayers(MinecraftServer server, UUID colonyId, StatisticsData data) {
        ColonyStatsSummary summary = data.computeSummary(colonyId);

        ColonyApi colonyApi = WandscapeApis.getColonyApiSilently();
        if (colonyApi == null) return;

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!PanelStateTracker.isPanelOpen(player)) continue;
            UUID playerColony = colonyApi.getColonyId(player.blockPosition());
            if (colonyId.equals(playerColony)) {
                PacketDistributor.sendToPlayer(player, new StatsSyncPacket(summary));
            }
        }
    }
}
