package com.wsteam.wandscape.warehouse.network;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import com.wsteam.wandscape.building.internal.BuildingSavedData;
import com.wsteam.wandscape.building.internal.BuildingState;
import com.wsteam.wandscape.engine.service.SoundService;
import com.wsteam.wandscape.engine.sound.WandscapeSounds;
import com.wsteam.wandscape.shared.api.WarehouseApi;
import com.wsteam.wandscape.shared.data.ElementType;
import com.wsteam.wandscape.shared.data.ItemKey;
import com.wsteam.wandscape.shared.registry.WandscapeApis;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import static com.wsteam.wandscape.Wandscape.MODID;
import com.wsteam.wandscape.shared.log.Log;

/**
 * Client→server packet for warehouse deposit/withdraw actions.
 *
 * <p>Sent when a player clicks Withdraw or Deposit in {@code WarehouseScreen}.
 * The server validates the building is a storage building (or a government town
 * hall acting as a warehouse for a colony without storage),
 * then performs the action and sends back a fresh {@link WarehouseDataPacket}.
 */
public record WarehouseActionPacket(
        BlockPos buildingPos,
        String action,       // "withdraw", "deposit", or "deposit_from_slot"
        String itemId,       // registry key of the item (informational for deposit)
        @Nullable CompoundTag nbt,
        int quantity,
        int slotIndex        // inventory slot index for deposit_from_slot; -1 otherwise
) implements CustomPacketPayload {

    private static final String TAG = "WarehouseActionPacket";

    public static final Type<WarehouseActionPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "warehouse_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, WarehouseActionPacket> STREAM_CODEC =
            StreamCodec.of(WarehouseActionPacket::write, WarehouseActionPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Server-side handler. */
    public static void handleServer(WarehouseActionPacket pkt, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer sp)) return;

        sp.getServer().execute(() -> {
            var level = sp.serverLevel();
            BuildingSavedData data = BuildingSavedData.get(level);
            UUID buildingId = data.getBuildingIdAt(pkt.buildingPos);
            if (buildingId == null) {
                Log.warn(TAG, "[WarehouseAction] no building at {}", pkt.buildingPos);
                return;
            }

            BuildingState state = data.getBuilding(buildingId);
            if (state == null || (!"storage".equals(state.getCategory())
                    && !"government".equals(state.getCategory()))) {
                Log.warn(TAG, "[WarehouseAction] building {} is not a storage (category={})",
                        buildingId, state != null ? state.getCategory() : "null");
                return;
            }

            UUID colonyId = state.getColonyId();
            if (colonyId == null) {
                Log.warn(TAG, "[WarehouseAction] storage building {} has no colony", buildingId);
                return;
            }

            WarehouseApi api = WandscapeApis.getWarehouseApiSilently();
            if (api == null) {
                Log.error(TAG, "[WarehouseAction] WarehouseApi not available");
                return;
            }

            switch (pkt.action) {
                case "withdraw" -> handleWithdraw(api, colonyId, pkt, sp);
                case "deposit" -> handleDeposit(api, colonyId, pkt, sp);
                case "deposit_from_slot" -> handleDepositFromSlot(api, colonyId, pkt, sp);
                default -> Log.warn(TAG, "[WarehouseAction] unknown action: {}", pkt.action);
            }
        });
    }

    // ── Withdraw ──────────────────────────────────────────────────────────

    private static void handleWithdraw(WarehouseApi api, UUID colonyId,
                                       WarehouseActionPacket pkt, ServerPlayer sp) {
        if (pkt.quantity <= 0) return;
        ItemKey key = ItemKey.of(pkt.itemId, pkt.nbt);

        long remaining = pkt.quantity;
        long totalTaken = 0;
        while (remaining > 0) {
            long take = Math.min(remaining, 64);
            if (!api.extractItem(colonyId, key, take, sp.getInventory())) {
                break; // no more available or inventory full
            }
            remaining -= take;
            totalTaken += take;
        }

        if (totalTaken > 0) {
            Log.info(TAG, "[WarehouseAction] withdraw {}x {} for player {} (colony={})",
                    totalTaken, pkt.itemId, sp.getName().getString(),
                    colonyId.toString().substring(0, 8));
            SoundService.playAt(sp.serverLevel(), pkt.buildingPos,
                    WandscapeSounds.WAREHOUSE, SoundSource.BLOCKS, 0.5f, 1.0f);
        }
        sendRefresh(api, colonyId, pkt.buildingPos, sp);
    }

    // ── Deposit ───────────────────────────────────────────────────────────

    private static void handleDeposit(WarehouseApi api, UUID colonyId,
                                       WarehouseActionPacket pkt, ServerPlayer sp) {
        // Security: use the server-side hand item, NOT the client's claim
        ItemStack handStack = sp.getMainHandItem();
        if (handStack.isEmpty()) {
            sp.displayClientMessage(
                    Component.literal("[Wandscape] Nothing in hand to deposit"), false);
            return;
        }

        var rl = BuiltInRegistries.ITEM.getKey(handStack.getItem());
        if (rl == null) return;

        int toDeposit = Math.min(handStack.getCount(),
                pkt.quantity > 0 ? pkt.quantity : handStack.getCount());

        ItemStack depositStack = handStack.copyWithCount(toDeposit);
        api.insertItems(colonyId, List.of(depositStack));

        // Remove deposited items from player's hand
        handStack.shrink(toDeposit);

        recordPlayerDeposit(colonyId, sp);

        Log.info(TAG, "[WarehouseAction] deposit {}x {} from player {} (colony={})",
                toDeposit, rl, sp.getName().getString(),
                colonyId.toString().substring(0, 8));

        SoundService.playAt(sp.serverLevel(), pkt.buildingPos,
                WandscapeSounds.WAREHOUSE, SoundSource.BLOCKS, 0.5f, 1.0f);

        sendRefresh(api, colonyId, pkt.buildingPos, sp);
    }

    // ── Deposit from inventory slot ────────────────────────────────────────

    /** Deposit items from a specific inventory slot (used by Exchange tab). */
    private static void handleDepositFromSlot(WarehouseApi api, UUID colonyId,
                                               WarehouseActionPacket pkt, ServerPlayer sp) {
        int slot = pkt.slotIndex();
        // Only allow hotbar (0-8) and main inventory (9-35)
        if (slot < 0 || slot > 35) return;

        ItemStack slotStack = sp.getInventory().getItem(slot);
        if (slotStack.isEmpty()) return;

        int toDeposit = Math.min(slotStack.getCount(), Math.max(1, pkt.quantity()));
        ItemStack depositStack = slotStack.copyWithCount(toDeposit);
        api.insertItems(colonyId, List.of(depositStack));
        slotStack.shrink(toDeposit);

        recordPlayerDeposit(colonyId, sp);

        Log.info(TAG, "[WarehouseAction] deposit_from_slot {}x {} from slot {} (colony={})",
                toDeposit, pkt.itemId(), slot,
                colonyId.toString().substring(0, 8));

        SoundService.playAt(sp.serverLevel(), pkt.buildingPos,
                WandscapeSounds.WAREHOUSE, SoundSource.BLOCKS, 0.5f, 1.0f);

        sendRefresh(api, colonyId, pkt.buildingPos, sp);
    }

    // ── Refresh ───────────────────────────────────────────────────────────

    /** Record a player warehouse deposit and re-push onboarding progress. */
    private static void recordPlayerDeposit(UUID colonyId, ServerPlayer sp) {
        var bank = com.wsteam.wandscape.warehouse.ColonyItemBank.get(sp.serverLevel());
        if (bank != null) bank.recordPlayerDeposit(colonyId);
        var guideApi = WandscapeApis.getGuideProgressApiSilently();
        if (guideApi != null) guideApi.sendToPlayer(sp, colonyId);
    }

    /** Send a fresh WarehouseDataPacket to the player so the GUI updates. */
    private static void sendRefresh(WarehouseApi api, UUID colonyId,
                                     BlockPos buildingPos, ServerPlayer sp) {
        // Re-query bank through the WarehouseManager's same snapshots
        // WarehouseApi is WarehouseManager which wraps ColonyItemBank
        Map<ItemKey, Long> itemSnapshot = Map.of();
        Map<ElementType, Long> elemSnapshot = Map.of();

        // We need direct bank access for snapshots
        var bank = com.wsteam.wandscape.warehouse.ColonyItemBank.get(sp.serverLevel());
        if (bank != null) {
            itemSnapshot = bank.getSnapshot(colonyId);
            elemSnapshot = bank.getElementSnapshot(colonyId);
        }

        var refreshPkt = WarehouseDataPacket.from(buildingPos, colonyId,
                itemSnapshot, elemSnapshot);
        PacketDistributor.sendToPlayer(sp, refreshPkt);
    }

    // ── StreamCodec helpers ───────────────────────────────────────────────

    static void write(RegistryFriendlyByteBuf buf, WarehouseActionPacket pkt) {
        buf.writeBlockPos(pkt.buildingPos);
        buf.writeUtf(pkt.action);
        buf.writeUtf(pkt.itemId);
        buf.writeNbt(pkt.nbt); // nullable
        buf.writeVarInt(pkt.quantity);
        buf.writeVarInt(pkt.slotIndex);
    }

    static WarehouseActionPacket read(RegistryFriendlyByteBuf buf) {
        return new WarehouseActionPacket(
                buf.readBlockPos(),
                buf.readUtf(),
                buf.readUtf(),
                buf.readNbt(),
                buf.readVarInt(),
                buf.readVarInt()
        );
    }
}
