package com.wsteam.wandscape.content.production.network;

import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.api.WandscapeApis;
import com.wsteam.wandscape.content.element.data.ElementType;
import com.wsteam.wandscape.content.production.ProductionRecipeManager;
import com.wsteam.wandscape.content.production.data.SynthesizeRecipe;
import com.wsteam.wandscape.content.warehouse.ColonyItemBank;
import com.wsteam.wandscape.foundation.networking.ClientPayloadDispatcher;
import com.wsteam.wandscape.foundation.networking.Net;
import com.wsteam.wandscape.foundation.util.ItemKey;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.*;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Server->Client packet carrying recipe book screen data:
 * available blueprint count + all synthesize recipes and their unlocked status.
 */
public record RecipeBookDataPacket(UUID colonyId, int blueprintCount, ListTag recipes, String creator)
        implements CustomPacketPayload {

    public static final Type<RecipeBookDataPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "recipe_book_data"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RecipeBookDataPacket> STREAM_CODEC =
            StreamCodec.of(RecipeBookDataPacket::write, RecipeBookDataPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleClient(RecipeBookDataPacket packet) {
        ClientPayloadDispatcher.dispatch(packet);
    }

    public record RecipeBookEntry(
            String id,
            String outputItem,
            Map<ElementType, Long> cost,
            boolean unlocked
    ) {}

    public List<RecipeBookEntry> recipeEntries() {
        List<RecipeBookEntry> result = new ArrayList<>();
        for (int i = 0; i < recipes.size(); i++) {
            CompoundTag tag = recipes.getCompound(i);
            String id = tag.getString("id");
            String output = tag.getString("output");
            boolean unlocked = tag.getBoolean("unlocked");
            Map<ElementType, Long> cost = new LinkedHashMap<>();
            if (tag.contains("cost")) {
                CompoundTag costTag = tag.getCompound("cost");
                for (String key : costTag.getAllKeys()) {
                    try {
                        ElementType type = ElementType.valueOf(key.toUpperCase());
                        cost.put(type, costTag.getLong(key));
                    } catch (IllegalArgumentException ignored) {}
                }
            }
            result.add(new RecipeBookEntry(id, output, cost, unlocked));
        }
        return result;
    }

    public static void sendTo(ServerPlayer player, UUID colonyId) {
        if (player == null || colonyId == null) return;
        var loader = Wandscape.PRODUCTION_RECIPE_LOADER;
        if (loader == null) return;

        int blueprintCount = countAvailableBlueprints(player, colonyId);

        ListTag recipeList = new ListTag();
        for (SynthesizeRecipe r : loader.getAllSynthesizeRecipes()) {
            boolean unlocked = ProductionRecipeManager.isSynthesizeUnlocked(colonyId, r.id());
            CompoundTag tag = new CompoundTag();
            tag.putString("id", r.id());
            tag.putString("output", r.outputItem());
            tag.putBoolean("unlocked", unlocked);

            CompoundTag costTag = new CompoundTag();
            for (var e : r.cost().entrySet()) {
                costTag.putLong(e.getKey().name().toLowerCase(), e.getValue());
            }
            tag.put("cost", costTag);
            recipeList.add(tag);
        }

        String creator = "";
        var colonyApi = WandscapeApis.getColonyApiSilently();
        if (colonyApi != null) {
            String name = colonyApi.getColonyName(colonyId);
            if (name != null) creator = name;
        }

        Net.toPlayer(player, new RecipeBookDataPacket(colonyId, blueprintCount, recipeList, creator));
    }

    public static int countAvailableBlueprints(ServerPlayer player, UUID colonyId) {
        int count = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (stack.is(Wandscape.ITEM_BLUEPRINT.get())) {
                count += stack.getCount();
            }
        }
        ColonyItemBank bank = ColonyItemBank.get(player.serverLevel());
        if (bank != null && colonyId != null) {
            count += (int) Math.min(Integer.MAX_VALUE,
                    bank.count(colonyId, ItemKey.of("wandscape:item_blueprint", null)));
        }
        return count;
    }

    static void write(RegistryFriendlyByteBuf buf, RecipeBookDataPacket pkt) {
        CompoundTag wrapper = new CompoundTag();
        wrapper.putUUID("colony", pkt.colonyId != null ? pkt.colonyId : new UUID(0, 0));
        wrapper.putInt("blueprints", pkt.blueprintCount);
        wrapper.put("recipes", pkt.recipes);
        wrapper.putString("creator", pkt.creator != null ? pkt.creator : "");
        buf.writeNbt(wrapper);
    }

    static RecipeBookDataPacket read(RegistryFriendlyByteBuf buf) {
        CompoundTag wrapper = buf.readNbt();
        if (wrapper == null) return new RecipeBookDataPacket(new UUID(0, 0), 0, new ListTag(), "");
        UUID colonyId = wrapper.contains("colony") ? wrapper.getUUID("colony") : new UUID(0, 0);
        int blueprints = wrapper.getInt("blueprints");
        ListTag recipes = wrapper.getList("recipes", Tag.TAG_COMPOUND);
        String creator = wrapper.getString("creator");
        return new RecipeBookDataPacket(colonyId, blueprints, recipes, creator);
    }
}
