package com.wsteam.wandscape.foundation.networking;

import com.wsteam.wandscape.compat.curios.CuriosCompat;
import com.wsteam.wandscape.content.building.network.AltarCastRequestPacket;
import com.wsteam.wandscape.content.building.network.AltarOpenPacket;
import com.wsteam.wandscape.content.building.network.BuildingAreaSyncPacket;
import com.wsteam.wandscape.content.building.network.BuildingConfigSyncChunkPacket;
import com.wsteam.wandscape.content.building.network.BuildingInfoPacket;
import com.wsteam.wandscape.content.building.network.ConstructionSiteDataPacket;
import com.wsteam.wandscape.content.building.network.HotelOpenPacket;
import com.wsteam.wandscape.content.building.network.MageHutActionPacket;
import com.wsteam.wandscape.content.building.network.MageHutDataPacket;
import com.wsteam.wandscape.content.building.network.NodeDataPacket;
import com.wsteam.wandscape.content.building.network.OpenWarehousePacket;
import com.wsteam.wandscape.content.building.network.RequestGatherTaskPacket;
import com.wsteam.wandscape.content.building.network.ShopMaxStockPacket;
import com.wsteam.wandscape.content.building.network.ShopOpenPacket;
import com.wsteam.wandscape.content.building.network.TaskQueueDataPacket;
import com.wsteam.wandscape.content.building.network.TaskQueueModifyPacket;
import com.wsteam.wandscape.content.building.network.TavernOpenPacket;
import com.wsteam.wandscape.content.building.network.TavernRecruitPacket;
import com.wsteam.wandscape.content.building.network.TownHallOpenPacket;
import com.wsteam.wandscape.content.building.network.TownHallReviveRequestPacket;
import com.wsteam.wandscape.content.building.network.TownHallReviveStatePacket;
import com.wsteam.wandscape.content.building.network.TownHallWarehouseRequestPacket;
import com.wsteam.wandscape.content.building.projection.network.BuildingActionPacket;
import com.wsteam.wandscape.content.building.projection.network.BuildingDebugRequestPacket;
import com.wsteam.wandscape.content.building.projection.network.BuildingDebugResponsePacket;
import com.wsteam.wandscape.content.building.projection.network.ProjectionEnterPacket;
import com.wsteam.wandscape.content.building.projection.network.ProjectionEnterResponsePacket;
import com.wsteam.wandscape.content.building.projection.network.ProjectionExitPacket;
import com.wsteam.wandscape.content.building.projection.network.ProjectionPlacePacket;
import com.wsteam.wandscape.content.building.projection.network.ProjectionSlotsRefreshPacket;
import com.wsteam.wandscape.content.building.scanner.network.ScannerExportPacket;
import com.wsteam.wandscape.content.building.scanner.network.ScannerSyncPacket;
import com.wsteam.wandscape.content.building.scanner.network.ScannerValuePacket;
import com.wsteam.wandscape.content.colony.exploration.network.ExplorationRewardPacket;
import com.wsteam.wandscape.content.colony.network.ColonyAmbientPacket;
import com.wsteam.wandscape.content.colony.network.ColonyCreatePromptPacket;
import com.wsteam.wandscape.content.colony.network.ColonyCreateRequestPacket;
import com.wsteam.wandscape.content.colony.network.ColonyNameUpdatePacket;
import com.wsteam.wandscape.content.colony.network.ColonySettingUpdatePacket;
import com.wsteam.wandscape.content.colony.network.ColonyStatsSyncPacket;
import com.wsteam.wandscape.content.colony.overview.network.OverviewEntityInteractPacket;
import com.wsteam.wandscape.content.colony.overview.network.OverviewInteractPacket;
import com.wsteam.wandscape.content.colony.stats.network.StatsSyncPacket;
import com.wsteam.wandscape.content.items.compass.network.CompassTargetPacket;
import com.wsteam.wandscape.content.items.guidebook.network.GuideBookOpenPacket;
import com.wsteam.wandscape.content.items.oathring.network.OathRingDataPacket;
import com.wsteam.wandscape.content.magic.network.MagicCircleCastPacket;
import com.wsteam.wandscape.content.npc.network.NpcDataPacket;
import com.wsteam.wandscape.content.npc.network.NpcDismissPacket;
import com.wsteam.wandscape.content.npc.network.NpcOpenEquipPacket;
import com.wsteam.wandscape.content.npc.network.NpcOpenInventoryPacket;
import com.wsteam.wandscape.content.npc.network.NpcOpenStrategyPacket;
import com.wsteam.wandscape.content.npc.network.NpcRenamePacket;
import com.wsteam.wandscape.content.npc.network.NpcStrategyPacket;
import com.wsteam.wandscape.content.npc.network.NpcTogglePacket;
import com.wsteam.wandscape.content.production.network.CraftingStationPacket;
import com.wsteam.wandscape.content.production.network.MagicStationPacket;
import com.wsteam.wandscape.content.production.network.RequestProductionTaskPacket;
import com.wsteam.wandscape.content.production.network.WorkstationDataPacket;
import com.wsteam.wandscape.content.road.network.DestroyFillPacket;
import com.wsteam.wandscape.content.road.network.FillBoxPacket;
import com.wsteam.wandscape.content.road.network.RoadAreaSyncPacket;
import com.wsteam.wandscape.content.road.network.RoadInteractPacket;
import com.wsteam.wandscape.content.road.network.RoadPlacePacket;
import com.wsteam.wandscape.content.road.network.RoadStudioEnterPacket;
import com.wsteam.wandscape.content.road.network.RoadWithdrawPacket;
import com.wsteam.wandscape.content.road.network.SplineBuildPacket;
import com.wsteam.wandscape.content.task.network.MageModeActionPacket;
import com.wsteam.wandscape.content.task.network.TaskManagementActionPacket;
import com.wsteam.wandscape.content.task.network.TaskManagementSyncPacket;
import com.wsteam.wandscape.content.task.network.TaskPanelSubscribePacket;
import com.wsteam.wandscape.content.tourist.network.TouristBubblePacket;
import com.wsteam.wandscape.content.tourist.network.TouristDataPacket;
import com.wsteam.wandscape.content.tutorial.network.TutorialProgressSyncPacket;
import com.wsteam.wandscape.content.tutorial.network.TutorialProgressUpdatePacket;
import com.wsteam.wandscape.content.warehouse.network.WarehouseActionPacket;
import com.wsteam.wandscape.content.warehouse.network.WarehouseDataPacket;
import com.wsteam.wandscape.content.warehouse.network.WarehouseTerminalKeyPacket;
import com.wsteam.wandscape.content.warehouse.transport.TransportStartPacket;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.log.LogCategory;
import com.wsteam.wandscape.foundation.networking.ParticleBurstPacket;
import com.wsteam.wandscape.foundation.networking.ScreenFeedbackPacket;
import com.wsteam.wandscape.foundation.registry.dataconfig.internal.DatapackDataSyncChunkPacket;
import com.wsteam.wandscape.foundation.ui.panel.PanelStateTogglePacket;
import com.wsteam.wandscape.foundation.ui.settings.network.ConfigSyncPacket;
import com.wsteam.wandscape.foundation.ui.settings.network.ConfigUpdatePacket;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * 全部网络包的注册表——86 个 payload 的类型契约与收发处理器在此一次登记。
 *
 * <p>集中在此的理由：注册所需的平台符号（{@code RegisterPayloadHandlersEvent} /
 * {@code PayloadRegistrar}）因此只出现在本文件与 {@code Wandscape} 的钩子签名里，
 * 而不是散进 86 个包类。包类只管「字段 + 怎么写怎么读」，不碰注册。
 *
 * <p>更彻底的一层：{@code IPayloadContext} 已从全库消失。处理器形参里那个上下文由
 * {@link #c2s} 的 lambda 推断得到，包类只收 {@link ServerPlayer}，永远见不到上下文类型。
 * 移植到别的 loader 时，「怎么从上下文取出玩家」只有本文件一处要改。
 *
 * <p>两条登记通道各一个 helper，把方向差异收在同一处：
 * <ul>
 *   <li>{@link #s2c} 服务端 → 客户端，处理器是包的静态 {@code handleClient(包类型)}。</li>
 *   <li>{@link #c2s} 客户端 → 服务端，处理器是包的静态 {@code handleServer(包类型, ServerPlayer)}。</li>
 * </ul>
 *
 * <p>public 是为了 {@code compat/curios} 复用同一条通道——Curios 只在加载时注册自己的包，
 * 但方向适配规则应与主干完全一致。
 *
 * <p>新增包时只在本表加一行，并依包归属域把处理器写进对应包类。
 */
public final class PayloadRegistry {

    private PayloadRegistry() {}

    /** 注册全部 payload。由 {@code Wandscape} 在 {@code RegisterPayloadHandlersEvent} 中调用。 */
    public static void register(RegisterPayloadHandlersEvent event) {
        var r = event.registrar(MODID).versioned("1.0");

        s2c(r, WarehouseDataPacket.TYPE, WarehouseDataPacket.STREAM_CODEC, WarehouseDataPacket::handleClient);
        s2c(r, WorkstationDataPacket.TYPE, WorkstationDataPacket.STREAM_CODEC, WorkstationDataPacket::handleClient);
        s2c(r, CraftingStationPacket.TYPE, CraftingStationPacket.STREAM_CODEC, CraftingStationPacket::handleClient);
        s2c(r, MagicStationPacket.TYPE, MagicStationPacket.STREAM_CODEC, MagicStationPacket::handleClient);
        s2c(r, ShopOpenPacket.TYPE, ShopOpenPacket.STREAM_CODEC, ShopOpenPacket::handleClient);
        s2c(r, BuildingConfigSyncChunkPacket.TYPE, BuildingConfigSyncChunkPacket.STREAM_CODEC, BuildingConfigSyncChunkPacket::handleClient);
        s2c(r, DatapackDataSyncChunkPacket.TYPE, DatapackDataSyncChunkPacket.STREAM_CODEC, DatapackDataSyncChunkPacket::handleClient);
        s2c(r, ExplorationRewardPacket.TYPE, ExplorationRewardPacket.STREAM_CODEC, ExplorationRewardPacket::handleClient);
        c2s(r, ShopMaxStockPacket.TYPE, ShopMaxStockPacket.STREAM_CODEC, ShopMaxStockPacket::handleServer);
        s2c(r, TavernOpenPacket.TYPE, TavernOpenPacket.STREAM_CODEC, TavernOpenPacket::handleClient);
        s2c(r, HotelOpenPacket.TYPE, HotelOpenPacket.STREAM_CODEC, HotelOpenPacket::handleClient);
        s2c(r, BuildingInfoPacket.TYPE, BuildingInfoPacket.STREAM_CODEC, BuildingInfoPacket::handleClient);
        s2c(r, TownHallOpenPacket.TYPE, TownHallOpenPacket.STREAM_CODEC, TownHallOpenPacket::handleClient);
        c2s(r, TownHallWarehouseRequestPacket.TYPE, TownHallWarehouseRequestPacket.STREAM_CODEC, TownHallWarehouseRequestPacket::handleServer);
        s2c(r, AltarOpenPacket.TYPE, AltarOpenPacket.STREAM_CODEC, AltarOpenPacket::handleClient);
        c2s(r, AltarCastRequestPacket.TYPE, AltarCastRequestPacket.STREAM_CODEC, AltarCastRequestPacket::handleServer);
        s2c(r, TaskQueueDataPacket.TYPE, TaskQueueDataPacket.STREAM_CODEC, TaskQueueDataPacket::handleClient);

        // ── Construction-site panel (under-construction building) ──
        s2c(r, ConstructionSiteDataPacket.TYPE, ConstructionSiteDataPacket.STREAM_CODEC, ConstructionSiteDataPacket::handleClient);
        c2s(r, RoadPlacePacket.TYPE, RoadPlacePacket.STREAM_CODEC, RoadPlacePacket::handleServer);
        s2c(r, OathRingDataPacket.TYPE, OathRingDataPacket.STREAM_CODEC, OathRingDataPacket::handleClient);
        s2c(r, CompassTargetPacket.TYPE, CompassTargetPacket.STREAM_CODEC, CompassTargetPacket::handleClient);
        c2s(r, DestroyFillPacket.TYPE, DestroyFillPacket.STREAM_CODEC, DestroyFillPacket::handleServer);
        c2s(r, FillBoxPacket.TYPE, FillBoxPacket.STREAM_CODEC, FillBoxPacket::handleServer);
        c2s(r, RequestProductionTaskPacket.TYPE, RequestProductionTaskPacket.STREAM_CODEC, RequestProductionTaskPacket::handleServer);
        c2s(r, TaskQueueModifyPacket.TYPE, TaskQueueModifyPacket.STREAM_CODEC, TaskQueueModifyPacket::handleServer);
        s2c(r, NodeDataPacket.TYPE, NodeDataPacket.STREAM_CODEC, NodeDataPacket::handleClient);
        c2s(r, RequestGatherTaskPacket.TYPE, RequestGatherTaskPacket.STREAM_CODEC, RequestGatherTaskPacket::handleServer);
        c2s(r, WarehouseActionPacket.TYPE, WarehouseActionPacket.STREAM_CODEC, WarehouseActionPacket::handleServer);
        c2s(r, WarehouseTerminalKeyPacket.TYPE, WarehouseTerminalKeyPacket.STREAM_CODEC, WarehouseTerminalKeyPacket::handleServer);
        c2s(r, TavernRecruitPacket.TYPE, TavernRecruitPacket.STREAM_CODEC, TavernRecruitPacket::handleServer);

        // ── Mage Hut ──
        s2c(r, MageHutDataPacket.TYPE, MageHutDataPacket.STREAM_CODEC, MageHutDataPacket::handleClient);
        c2s(r, MageHutActionPacket.TYPE, MageHutActionPacket.STREAM_CODEC, MageHutActionPacket::handleServer);
        c2s(r, OpenWarehousePacket.TYPE, OpenWarehousePacket.STREAM_CODEC, OpenWarehousePacket::handleServer);

        // ── Soul Projection ──
        c2s(r, ProjectionEnterPacket.TYPE, ProjectionEnterPacket.STREAM_CODEC, ProjectionEnterPacket::handleServer);
        s2c(r, ProjectionEnterResponsePacket.TYPE, ProjectionEnterResponsePacket.STREAM_CODEC, ProjectionEnterResponsePacket::handleClient);
        c2s(r, ProjectionExitPacket.TYPE, ProjectionExitPacket.STREAM_CODEC, ProjectionExitPacket::handleServer);
        c2s(r, ProjectionPlacePacket.TYPE, ProjectionPlacePacket.STREAM_CODEC, ProjectionPlacePacket::handleServer);
        s2c(r, ProjectionSlotsRefreshPacket.TYPE, ProjectionSlotsRefreshPacket.STREAM_CODEC, ProjectionSlotsRefreshPacket::handleClient);

        // ── Overview ──
        c2s(r, OverviewInteractPacket.TYPE, OverviewInteractPacket.STREAM_CODEC, OverviewInteractPacket::handleServer);
        c2s(r, OverviewEntityInteractPacket.TYPE, OverviewEntityInteractPacket.STREAM_CODEC, OverviewEntityInteractPacket::handleServer);
        c2s(r, BuildingDebugRequestPacket.TYPE, BuildingDebugRequestPacket.STREAM_CODEC, BuildingDebugRequestPacket::handleServer);
        s2c(r, BuildingDebugResponsePacket.TYPE, BuildingDebugResponsePacket.STREAM_CODEC, BuildingDebugResponsePacket::handleClient);
        c2s(r, BuildingActionPacket.TYPE, BuildingActionPacket.STREAM_CODEC, BuildingActionPacket::handleServer);

        // ── Building Scanner ──
        c2s(r, ScannerSyncPacket.TYPE, ScannerSyncPacket.STREAM_CODEC, ScannerSyncPacket::handleServer);
        c2s(r, ScannerExportPacket.TYPE, ScannerExportPacket.STREAM_CODEC, ScannerExportPacket::handleServer);
        c2s(r, ScannerValuePacket.TYPE, ScannerValuePacket.STREAM_CODEC, ScannerValuePacket::handleServer);
        c2s(r, SplineBuildPacket.TYPE, SplineBuildPacket.STREAM_CODEC, SplineBuildPacket::handleServer);
        c2s(r, RoadInteractPacket.TYPE, RoadInteractPacket.STREAM_CODEC, RoadInteractPacket::handleServer);
        c2s(r, RoadWithdrawPacket.TYPE, RoadWithdrawPacket.STREAM_CODEC, RoadWithdrawPacket::handleServer);

        // ── Wandscape Panel ──
        c2s(r, PanelStateTogglePacket.TYPE, PanelStateTogglePacket.STREAM_CODEC, PanelStateTogglePacket::handleServer);
        s2c(r, ColonyStatsSyncPacket.TYPE, ColonyStatsSyncPacket.STREAM_CODEC, ColonyStatsSyncPacket::handleClient);

        // ── Stats ──
        s2c(r, StatsSyncPacket.TYPE, StatsSyncPacket.STREAM_CODEC, StatsSyncPacket::handleClient);

        // ── Building interaction area overlay ──
        s2c(r, BuildingAreaSyncPacket.TYPE, BuildingAreaSyncPacket.STREAM_CODEC, BuildingAreaSyncPacket::handleClient);

        // ── Road construction ghost sync ──
        s2c(r, RoadAreaSyncPacket.TYPE, RoadAreaSyncPacket.STREAM_CODEC, RoadAreaSyncPacket::handleClient);

        // ── Transient action feedback (screen toast or action bar) ──
        s2c(r, ScreenFeedbackPacket.TYPE, ScreenFeedbackPacket.STREAM_CODEC, ScreenFeedbackPacket::handleClient);

        // ── NPC info screen ──
        s2c(r, NpcDataPacket.TYPE, NpcDataPacket.STREAM_CODEC, NpcDataPacket::handleClient);
        c2s(r, NpcOpenStrategyPacket.TYPE, NpcOpenStrategyPacket.STREAM_CODEC, NpcOpenStrategyPacket::handleServer);
        c2s(r, NpcOpenInventoryPacket.TYPE, NpcOpenInventoryPacket.STREAM_CODEC, NpcOpenInventoryPacket::handleServer);
        c2s(r, NpcStrategyPacket.TYPE, NpcStrategyPacket.STREAM_CODEC, NpcStrategyPacket::handleServer);
        c2s(r, NpcRenamePacket.TYPE, NpcRenamePacket.STREAM_CODEC, NpcRenamePacket::handleServer);
        c2s(r, NpcTogglePacket.TYPE, NpcTogglePacket.STREAM_CODEC, NpcTogglePacket::handleServer);
        c2s(r, NpcDismissPacket.TYPE, NpcDismissPacket.STREAM_CODEC, NpcDismissPacket::handleServer);

        // ── Tourist info screen ──
        s2c(r, TouristDataPacket.TYPE, TouristDataPacket.STREAM_CODEC, TouristDataPacket::handleClient);

        // ── Tourist purchase / service bubble ──
        s2c(r, TouristBubblePacket.TYPE, TouristBubblePacket.STREAM_CODEC, TouristBubblePacket::handleClient);

        // ── Colony day/night ambient ──
        s2c(r, ColonyAmbientPacket.TYPE, ColonyAmbientPacket.STREAM_CODEC, ColonyAmbientPacket::handleClient);

        // ── Colony name update ──
        c2s(r, ColonyNameUpdatePacket.TYPE, ColonyNameUpdatePacket.STREAM_CODEC, ColonyNameUpdatePacket::handleServer);

        // ── Colony settings update (settings center 「本镇」page) ──
        c2s(r, ColonySettingUpdatePacket.TYPE, ColonySettingUpdatePacket.STREAM_CODEC, ColonySettingUpdatePacket::handleServer);

        // ── Town hall bootstrap revive (anti-deadlock, all wizards dead) ──
        c2s(r, TownHallReviveRequestPacket.TYPE, TownHallReviveRequestPacket.STREAM_CODEC, TownHallReviveRequestPacket::handleServer);
        s2c(r, TownHallReviveStatePacket.TYPE, TownHallReviveStatePacket.STREAM_CODEC, TownHallReviveStatePacket::handleClient);

        // ── Colony create (town hall naming flow) ──
        c2s(r, ColonyCreateRequestPacket.TYPE, ColonyCreateRequestPacket.STREAM_CODEC, ColonyCreateRequestPacket::handleServer);
        s2c(r, ColonyCreatePromptPacket.TYPE, ColonyCreatePromptPacket.STREAM_CODEC, ColonyCreatePromptPacket::handleClient);

        // ── Transport start ──
        s2c(r, TransportStartPacket.TYPE, TransportStartPacket.STREAM_CODEC, TransportStartPacket::handleClient);

        // ── Magic circle cast ──
        s2c(r, MagicCircleCastPacket.TYPE, MagicCircleCastPacket.STREAM_CODEC, MagicCircleCastPacket::handleClient);

        // ── Particle burst (colored FX) ──
        s2c(r, ParticleBurstPacket.TYPE, ParticleBurstPacket.STREAM_CODEC, ParticleBurstPacket::handleClient);

        // ── Guide book (right-click to open tutorial home) ──
        s2c(r, GuideBookOpenPacket.TYPE, GuideBookOpenPacket.STREAM_CODEC, GuideBookOpenPacket::handleClient);

        // ── Guide progress (onboarding persistence) ──
        s2c(r, TutorialProgressSyncPacket.TYPE, TutorialProgressSyncPacket.STREAM_CODEC, TutorialProgressSyncPacket::handleClient);
        c2s(r, TutorialProgressUpdatePacket.TYPE, TutorialProgressUpdatePacket.STREAM_CODEC, TutorialProgressUpdatePacket::handleServer);

        // ── Spline Road Editor ──
        s2c(r, RoadStudioEnterPacket.TYPE, RoadStudioEnterPacket.STREAM_CODEC, RoadStudioEnterPacket::handleClient);

        // ── Task & Mage Management Panel ──
        c2s(r, TaskPanelSubscribePacket.TYPE, TaskPanelSubscribePacket.STREAM_CODEC, TaskPanelSubscribePacket::handleServer);
        s2c(r, TaskManagementSyncPacket.TYPE, TaskManagementSyncPacket.STREAM_CODEC, TaskManagementSyncPacket::handleClient);
        c2s(r, TaskManagementActionPacket.TYPE, TaskManagementActionPacket.STREAM_CODEC, TaskManagementActionPacket::handleServer);
        c2s(r, MageModeActionPacket.TYPE, MageModeActionPacket.STREAM_CODEC, MageModeActionPacket::handleServer);

        // ── NPC 装备界面重开（饰品屏返回按钮） ──
        c2s(r, NpcOpenEquipPacket.TYPE, NpcOpenEquipPacket.STREAM_CODEC, NpcOpenEquipPacket::handleServer);

        // ── 设置中心配置同步 ──
        c2s(r, ConfigUpdatePacket.TYPE, ConfigUpdatePacket.STREAM_CODEC, ConfigUpdatePacket::handleServer);
        s2c(r, ConfigSyncPacket.TYPE, ConfigSyncPacket.STREAM_CODEC, ConfigSyncPacket::handleClient);

        // Curios 兼容：法师饰品栏打开请求（仅 Curios 加载时在实现类内注册；无 Curios 时此处不引用任何 Curios 类）
        CuriosCompat.registerPayloads(r);
    }

    /** 登记一条服务端 → 客户端的包。 */
    public static <T extends CustomPacketPayload> void s2c(
            PayloadRegistrar r,
            CustomPacketPayload.Type<T> type,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
            Consumer<T> handler) {
        r.playToClient(type, codec, (payload, ctx) -> handler.accept(payload));
    }

    /**
     * 登记一条客户端 → 服务端的包。
     *
     * <p>上下文里的玩家只在服务端的 serverbound 路径上存在，取到的必须是 {@link ServerPlayer}；
     * 真拿到别的形态说明包走错了方向，记警告后丢弃，不打断整条连接。
     */
    public static <T extends CustomPacketPayload> void c2s(
            PayloadRegistrar r,
            CustomPacketPayload.Type<T> type,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
            BiConsumer<T, ServerPlayer> handler) {
        r.playToServer(type, codec, (payload, ctx) -> {
            if (ctx.player() instanceof ServerPlayer player) {
                handler.accept(payload, player);
            } else {
                Log.warnOnce(LogCategory.NETWORK, "not-server-player:" + type.id(),
                        "Serverbound payload {} arrived without a ServerPlayer — dropped", type.id());
            }
        });
    }
}
