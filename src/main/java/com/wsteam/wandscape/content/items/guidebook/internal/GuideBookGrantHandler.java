package com.wsteam.wandscape.content.items.guidebook.internal;

import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.foundation.log.Log;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * 玩家进游戏即赠一本指南书，不需要自己先合成。
 *
 * <p>只在**首次登录该存档**发一次：标记写进玩家持久化 NBT（随玩家数据存档），所以之后每次登录
 * 都不会再补发——手册弄丢了照原配方重做一本（配方保留就是这个用途）。
 * 旧档玩家没有这个标记，升级后第一次登录会补到一本。
 */
@EventBusSubscriber(modid = Wandscape.MODID)
public final class GuideBookGrantHandler {

    private static final String TAG = "GuideBookGrant";
    /** 玩家持久化 NBT 里的「已发过」标记。 */
    private static final String GRANTED_KEY = "wandscape_guide_book_granted";

    private GuideBookGrantHandler() {}

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        CompoundTag data = player.getPersistentData();
        if (data.getBoolean(GRANTED_KEY)) return;
        data.putBoolean(GRANTED_KEY, true);

        ItemStack book = new ItemStack(Wandscape.GUIDE_BOOK.get());
        if (!player.addItem(book)) {
            player.drop(book, false);
        }
        Log.info(TAG, "Granted guide book to {} (first join)", player.getGameProfile().getName());
    }
}
