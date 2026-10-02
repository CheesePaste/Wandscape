package com.wsteam.wandscape.content.building.network;
import com.wsteam.wandscape.content.task.component.Position;

import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.networking.ClientPayloadDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Server→client packet carrying the current task queue for a building.
 * Each entry carries structured fields so the client can render
 * an icon + short label instead of a potentially truncated text summary.
 *
 * <p>Sent after any {@link TaskQueueModifyPacket} action and also on initial refresh.
 */
public record TaskQueueDataPacket(
    BlockPos stationPos,
    List<QueueEntry> entries,
    List<CurrentTask> currents
) implements CustomPacketPayload {

    private static final String TAG = "TaskQueueDataPacket";

    public static final Type<TaskQueueDataPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "task_queue_data"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TaskQueueDataPacket> STREAM_CODEC =
            StreamCodec.of(TaskQueueDataPacket::write, TaskQueueDataPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * One entry in the task queue.
     *
     * @param index           position in the FIFO queue (0 = current task)
     * @param category        short category key: "decompose" / "synthesize" / "craft" / "brew" / "build" / "gather"
     * @param itemOrRecipeId  the item or recipe resource id (e.g. "minecraft:stone_bricks")
     * @param quantity        number of units involved
     * @param blueprintId     full blueprint id (for internal/debug use)
     * @param summary         human-readable fallback label
     * @param insufficient    true when an element-costing recipe cannot afford its quantity with current elements
     * @param missingElements element ids (lowercase, e.g. "wood") that are short — rendered as icons client-side
     * @param capacityBlocked true when the colony warehouse is full and this craft output cannot be deposited
     *                        (restock-driven synthesis is exempt)
     * @param sourceType      origin type: "player" / "building" / "road" / "restock" / "auto"
     * @param sourceId        origin id: buildingId / roadId (empty for player/auto)
     * @param sourceName      display name for the source: e.g. "Town Hall", "Road Construction", etc.
     */
    public record QueueEntry(
            int index,
            String category,
            String itemOrRecipeId,
            int quantity,
            String blueprintId,
            String summary,
            boolean insufficient,
            List<String> missingElements,
            boolean capacityBlocked,
            String sourceType,
            String sourceId,
            String sourceName
    ) {
        /** Compact constructor defaulting source info to player. */
        public QueueEntry(int index, String category, String itemOrRecipeId, int quantity,
                          String blueprintId, String summary, boolean insufficient,
                          List<String> missingElements, boolean capacityBlocked) {
            this(index, category, itemOrRecipeId, quantity, blueprintId, summary,
                    insufficient, missingElements, capacityBlocked, "player", "", "");
        }

        /** Legacy constructor kept for backward compatibility. */
        public QueueEntry(int index, String category, String itemOrRecipeId, int quantity,
                          String blueprintId, String summary) {
            this(index, category, itemOrRecipeId, quantity, blueprintId, summary, false, List.of(), false, "player", "", "");
        }
    }

    /**
     * The building's currently executing (head) task.
     * {@code entry} mirrors the same display fields as a {@link QueueEntry}.
     * Progress is either channel-based ({@code channelTotalTicks > 0}) or step-based.
     *
     * <p>{@code pending} is true when the task has a channel configured
     * ({@code channelTotalTicks > 0}) but the channel has not started yet
     * (e.g. the NPC is still travelling to the station). The client shows a
     * "waiting" label instead of a progress bar + countdown in that state.
     */
    public record CurrentTask(
            QueueEntry entry,
            int stepIndex,
            int totalSteps,
            int channelRemainingTicks,
            int channelTotalTicks,
            boolean pending
    ) {}



    public static void handleClient(TaskQueueDataPacket packet) {
        ClientPayloadDispatcher.dispatch(packet);
    }

    static void write(RegistryFriendlyByteBuf buf, TaskQueueDataPacket pkt) {
        buf.writeBlockPos(pkt.stationPos);
        buf.writeVarInt(pkt.entries.size());
        for (TaskQueueDataPacket.QueueEntry entry : pkt.entries) {
            writeEntry(buf, entry);
        }
        buf.writeVarInt(pkt.currents.size());
        for (CurrentTask current : pkt.currents) {
            writeEntry(buf, current.entry());
            buf.writeVarInt(current.stepIndex());
            buf.writeVarInt(current.totalSteps());
            buf.writeVarInt(current.channelRemainingTicks());
            buf.writeVarInt(current.channelTotalTicks());
            buf.writeBoolean(current.pending());
        }
    }

    private static void writeEntry(RegistryFriendlyByteBuf buf, QueueEntry entry) {
        buf.writeVarInt(entry.index);
        buf.writeUtf(entry.category);
        buf.writeUtf(entry.itemOrRecipeId);
        buf.writeVarInt(entry.quantity);
        buf.writeUtf(entry.blueprintId);
        buf.writeUtf(entry.summary);
        buf.writeBoolean(entry.insufficient);
        buf.writeVarInt(entry.missingElements.size());
        for (String el : entry.missingElements) {
            buf.writeUtf(el);
        }
        buf.writeBoolean(entry.capacityBlocked);
        buf.writeUtf(entry.sourceType != null ? entry.sourceType : "");
        buf.writeUtf(entry.sourceId != null ? entry.sourceId : "");
        buf.writeUtf(entry.sourceName != null ? entry.sourceName : "");
    }

    static TaskQueueDataPacket read(RegistryFriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        int size = buf.readVarInt();
        List<QueueEntry> entries = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            entries.add(readEntry(buf));
        }
        int currentCount = buf.readVarInt();
        List<CurrentTask> currents = new ArrayList<>(currentCount);
        for (int i = 0; i < currentCount; i++) {
            currents.add(new CurrentTask(
                    readEntry(buf),
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readBoolean()));
        }
        return new TaskQueueDataPacket(pos, entries, currents);
    }

    private static QueueEntry readEntry(RegistryFriendlyByteBuf buf) {
        int index = buf.readVarInt();
        String category = buf.readUtf();
        String itemOrRecipeId = buf.readUtf();
        int quantity = buf.readVarInt();
        String blueprintId = buf.readUtf();
        String summary = buf.readUtf();
        boolean insufficient = buf.readBoolean();
        int missingCount = buf.readVarInt();
        List<String> missing = new ArrayList<>(missingCount);
        for (int i = 0; i < missingCount; i++) {
            missing.add(buf.readUtf());
        }
        boolean capacityBlocked = buf.readBoolean();
        String sourceType = buf.readUtf();
        String sourceId = buf.readUtf();
        String sourceName = buf.readUtf();
        return new QueueEntry(index, category, itemOrRecipeId, quantity, blueprintId, summary,
                insufficient, missing, capacityBlocked, sourceType, sourceId, sourceName);
    }
}
