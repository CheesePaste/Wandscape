package com.wsteam.wandscape.building.network;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.shared.data.WorkItem;
import org.slf4j.Logger;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

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
    List<QueueEntry> entries
) implements CustomPacketPayload {

    private static final Logger LOGGER = LogUtils.getLogger();

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
     * @param index          position in the FIFO queue (0 = current task)
     * @param category       short category key: "decompose" / "synthesize" / "craft" / "brew" / "build" / "gather"
     * @param itemOrRecipeId the item or recipe resource id (e.g. "minecraft:stone_bricks")
     * @param quantity       number of units involved
     * @param blueprintId    full blueprint id (for internal/debug use)
     * @param summary        human-readable fallback label
     */
    public record QueueEntry(
            int index,
            String category,
            String itemOrRecipeId,
            int quantity,
            String blueprintId,
            String summary
    ) {}

    private static Consumer<TaskQueueDataPacket> clientHandler;

    public static void setClientHandler(Consumer<TaskQueueDataPacket> handler) {
        clientHandler = handler;
    }

    public static void handleClient(TaskQueueDataPacket packet) {
        if (clientHandler != null) {
            clientHandler.accept(packet);
        } else {
            LOGGER.warn("TaskQueueDataPacket: no client handler registered");
        }
    }

    static void write(RegistryFriendlyByteBuf buf, TaskQueueDataPacket pkt) {
        buf.writeBlockPos(pkt.stationPos);
        buf.writeVarInt(pkt.entries.size());
        for (TaskQueueDataPacket.QueueEntry entry : pkt.entries) {
            buf.writeVarInt(entry.index);
            buf.writeUtf(entry.category);
            buf.writeUtf(entry.itemOrRecipeId);
            buf.writeVarInt(entry.quantity);
            buf.writeUtf(entry.blueprintId);
            buf.writeUtf(entry.summary);
        }
    }

    static TaskQueueDataPacket read(RegistryFriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        int size = buf.readVarInt();
        List<QueueEntry> entries = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            int index = buf.readVarInt();
            String category = buf.readUtf();
            String itemOrRecipeId = buf.readUtf();
            int quantity = buf.readVarInt();
            String blueprintId = buf.readUtf();
            String summary = buf.readUtf();
            entries.add(new QueueEntry(index, category, itemOrRecipeId, quantity, blueprintId, summary));
        }
        return new TaskQueueDataPacket(pos, entries);
    }
}
