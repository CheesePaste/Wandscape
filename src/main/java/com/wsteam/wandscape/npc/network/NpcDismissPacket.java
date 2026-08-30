package com.wsteam.wandscape.npc.network;

import com.wsteam.wandscape.building.internal.BuildingSavedData;
import com.wsteam.wandscape.building.internal.BuildingState;
import com.wsteam.wandscape.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.shared.data.MageHutResident;
import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.shared.network.ScreenFeedbackPacket;
import com.wsteam.wandscape.shared.ui.I18n;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import static com.wsteam.wandscape.Wandscape.MODID;
/**
 * Client→server packet: dismiss (解雇) an NPC from the NPC info screen.
 *
 * <p>Server drops the NPC's equipment (armor / custom wand / equipped spell
 * scrolls), frees the mage hut binding if this NPC was its resident, then
 * permanently removes the entity. Dismissal uses {@code discard()} — not the
 * death flow — so no {@code DeathRecord} is written and the mage cannot be
 * brought back by the revive magic / colony wipe fallback.
 */
public record NpcDismissPacket(int entityId) implements CustomPacketPayload {

    private static final String TAG = "NpcDismissPacket";

    public static final Type<NpcDismissPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "npc_dismiss"));

    public static final StreamCodec<RegistryFriendlyByteBuf, NpcDismissPacket> STREAM_CODEC =
            StreamCodec.of(NpcDismissPacket::write, NpcDismissPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    // ── StreamCodec helpers ──

    static void write(RegistryFriendlyByteBuf buf, NpcDismissPacket pkt) {
        buf.writeInt(pkt.entityId);
    }

    static NpcDismissPacket read(RegistryFriendlyByteBuf buf) {
        return new NpcDismissPacket(buf.readInt());
    }

    // ── Server handler ──

    public static void handleServer(NpcDismissPacket packet, ServerPlayer player) {
        if (player == null || player.isRemoved()) return;

        var level = player.serverLevel();
        var entity = level.getEntity(packet.entityId());
        if (!(entity instanceof WandscapeNpc npc)) {
            Log.warn(TAG, "Dismiss target entity {} is not a WandscapeNpc", packet.entityId());
            return;
        }
        if (!npc.isColonyNpc()) {
            Log.warn(TAG, "Dismiss target {} is not a colony NPC", npc.getUUID().toString().substring(0, 8));
            return;
        }

        String name = npc.getNpcName();

        // 若该法师是某法师小屋的入住者，释放小屋使其恢复空置（入住记录不会随解雇保留）
        BuildingSavedData savedData = BuildingSavedData.get(level);
        if (savedData != null) {
            for (BuildingState b : savedData.getAllBuildings()) {
                MageHutResident resident = savedData.getMageHutResident(b.getBuildingId());
                if (resident != null && npc.getUUID().equals(resident.npcId())) {
                    savedData.removeMageHutResident(b.getBuildingId());
                    Log.info(TAG, "NPC {} freed mage hut {} on dismissal",
                            npc.getUUID().toString().substring(0, 8),
                            b.getBuildingId().toString().substring(0, 8));
                    break;
                }
            }
        }

        npc.dismissFromColony();
        Log.info(TAG, "NPC {} ({}) dismissed by {}",
                npc.getUUID().toString().substring(0, 8), name, player.getName().getString());
        ScreenFeedbackPacket.send(player,
                I18n.name("message.wandscape.npc.dismissed", "[Wandscape] Dismissed %s", name),
                false);
    }
}
