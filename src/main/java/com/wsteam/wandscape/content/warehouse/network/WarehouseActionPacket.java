package com.wsteam.wandscape.content.warehouse.network;

import com.wsteam.wandscape.shared.data.ItemKey;
import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.content.warehouse.WarehouseMenu;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import javax.annotation.Nullable;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Client→server packet for warehouse slot interactions (AE2-style custom clicks;
 * warehouse slots are read-only in the menu so vanilla click packets can't be used).
 *
 * <p>The server validates the click against the player's open {@link WarehouseMenu}
 * by container id, executes the action through the menu, and sends back a fresh
 * {@link WarehouseDataPacket}.
 */
public record WarehouseActionPacket(
        int containerId,
        String action,          // one of the ACTION_* constants
        String itemId,
        @Nullable CompoundTag nbt,
        int param               // action 附加参数（如目标玩家槽索引）；无则 0
) implements CustomPacketPayload {

    private static final String TAG = "WarehouseActionPacket";

    /** Take the whole entry into the cursor (left-click on empty cursor). */
    public static final String ACTION_CURSOR_TAKE_ALL = "cursor_take_all";
    /** Take half (rounded up) into the cursor (right-click on empty cursor). */
    public static final String ACTION_CURSOR_TAKE_HALF = "cursor_take_half";
    /** Shift-click: take the whole entry into the player inventory (64-chunks). */
    public static final String ACTION_TAKE_TO_INVENTORY = "take_to_inventory";
    /** Left-click with a carried stack: deposit the whole cursor. */
    public static final String ACTION_CURSOR_DEPOSIT_ALL = "cursor_deposit_all";
    /** Right-click with a carried stack: deposit one item from the cursor. */
    public static final String ACTION_CURSOR_DEPOSIT_ONE = "cursor_deposit_one";
    /** Scroll (shift+up) on a grid entry: deposit all matching player-inventory items. */
    public static final String ACTION_DEPOSIT_INVENTORY_TYPE = "deposit_inventory_type";
    /** Scroll (shift+up) on a player slot: deposit that slot. param = slot index. */
    public static final String ACTION_DEPOSIT_SLOT = "deposit_slot";
    /** Scroll (shift+down) on a player slot: take the entry into that slot. param = slot index. */
    public static final String ACTION_TAKE_TO_SLOT = "take_to_slot";

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
            if (!(sp.containerMenu instanceof WarehouseMenu menu)
                    || menu.containerId != pkt.containerId) {
                Log.warn(TAG, "[WarehouseAction] container mismatch (id={}, menu={})",
                        pkt.containerId, sp.containerMenu);
                return;
            }

            ItemKey key = ItemKey.of(pkt.itemId, pkt.nbt);
            switch (pkt.action) {
                case ACTION_CURSOR_TAKE_ALL -> menu.cursorTakeAll(key, sp);
                case ACTION_CURSOR_TAKE_HALF -> menu.cursorTakeHalf(key, sp);
                case ACTION_TAKE_TO_INVENTORY -> menu.takeToInventory(key, sp);
                case ACTION_CURSOR_DEPOSIT_ALL -> menu.cursorDepositAll(sp);
                case ACTION_CURSOR_DEPOSIT_ONE -> menu.cursorDepositOne(sp);
                case ACTION_DEPOSIT_INVENTORY_TYPE -> menu.depositInventoryType(key, sp);
                case ACTION_DEPOSIT_SLOT -> menu.depositSlot(pkt.param, sp);
                case ACTION_TAKE_TO_SLOT -> menu.takeToSlot(key, sp, pkt.param);
                default -> Log.warn(TAG, "[WarehouseAction] unknown action: {}", pkt.action);
            }
            menu.sendRefresh(sp);
        });
    }

    // ── StreamCodec helpers ──

    static void write(RegistryFriendlyByteBuf buf, WarehouseActionPacket pkt) {
        buf.writeVarInt(pkt.containerId);
        buf.writeUtf(pkt.action);
        buf.writeUtf(pkt.itemId);
        buf.writeNbt(pkt.nbt); // nullable
        buf.writeVarInt(pkt.param);
    }

    static WarehouseActionPacket read(RegistryFriendlyByteBuf buf) {
        return new WarehouseActionPacket(
                buf.readVarInt(),
                buf.readUtf(),
                buf.readUtf(),
                buf.readNbt(),
                buf.readVarInt()
        );
    }
}
