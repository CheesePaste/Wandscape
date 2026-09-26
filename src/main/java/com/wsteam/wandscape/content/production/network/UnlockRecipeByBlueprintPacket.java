package com.wsteam.wandscape.content.production.network;

import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.content.colony.ownership.ColonyOwnership;
import com.wsteam.wandscape.content.production.ProductionRecipeManager;
import com.wsteam.wandscape.content.warehouse.ColonyItemBank;
import com.wsteam.wandscape.foundation.networking.ScreenFeedbackPacket;
import com.wsteam.wandscape.foundation.ui.I18n;
import com.wsteam.wandscape.foundation.util.ItemKey;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Client->Server packet requesting consumption of 1 blueprint to unlock a synthesize recipe.
 */
public record UnlockRecipeByBlueprintPacket(UUID colonyId, String recipeId) implements CustomPacketPayload {

    public static final Type<UnlockRecipeByBlueprintPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "unlock_recipe_by_blueprint"));

    public static final StreamCodec<RegistryFriendlyByteBuf, UnlockRecipeByBlueprintPacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, pkt) -> {
                        buf.writeUUID(pkt.colonyId());
                        buf.writeUtf(pkt.recipeId());
                    },
                    buf -> new UnlockRecipeByBlueprintPacket(buf.readUUID(), buf.readUtf())
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleServer(UnlockRecipeByBlueprintPacket pkt, ServerPlayer player) {
        if (player.getServer() == null) return;
        player.getServer().execute(() -> {
            if (pkt.colonyId() == null || pkt.recipeId() == null || pkt.recipeId().isEmpty()) return;

            if (!ColonyOwnership.isOwn(pkt.colonyId(), player)) {
                ColonyOwnership.deny(player, "recipe_book", "配方");
                return;
            }

            if (ProductionRecipeManager.isSynthesizeUnlocked(pkt.colonyId(), pkt.recipeId())) {
                RecipeBookDataPacket.sendTo(player, pkt.colonyId());
                return;
            }

            boolean deducted = false;
            // 1. Try deducting from player's inventory
            for (int i = 0; i < player.getInventory().items.size(); i++) {
                ItemStack stack = player.getInventory().items.get(i);
                if (stack.is(Wandscape.ITEM_BLUEPRINT.get())) {
                    stack.shrink(1);
                    deducted = true;
                    break;
                }
            }

            // 2. Try deducting from colony warehouse
            if (!deducted) {
                ColonyItemBank bank = ColonyItemBank.get(player.serverLevel());
                if (bank != null) {
                    ItemKey key = ItemKey.of("wandscape:item_blueprint", null);
                    deducted = bank.consume(pkt.colonyId(), key, 1);
                }
            }

            if (!deducted) {
                ScreenFeedbackPacket.send(player,
                        I18n.name("gui.wandscape.recipe.no_blueprint", "缺少物品图纸"), true);
                return;
            }

            // Permanently unlock recipe
            ProductionRecipeManager.unlockSynthesize(pkt.colonyId(), pkt.recipeId(),
                    ProductionRecipeManager.SOURCE_BLUEPRINT);

            // Play sound effect
            player.serverLevel().playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.7f, 1.2f);

            ScreenFeedbackPacket.send(player,
                    I18n.name("gui.wandscape.recipe.unlocked_success", "配方解锁成功"), false);

            // Sync updated recipe book to client
            RecipeBookDataPacket.sendTo(player, pkt.colonyId());
        });
    }
}
