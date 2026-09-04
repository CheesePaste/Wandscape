package com.wsteam.wandscape.content.npc.guard;

import com.wsteam.wandscape.Config;
import com.wsteam.wandscape.api.ColonyApi;
import com.wsteam.wandscape.api.WandscapeApis;
import com.wsteam.wandscape.compat.goety.GoetyCompat;
import com.wsteam.wandscape.compat.ironspellbooks.IronSpellsCompat;
import com.wsteam.wandscape.content.npc.entity.WandscapeNpc;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.UUID;

/**
 * 玩家误伤自家殖民地 NPC 的兜底拦截（配合 {@link WandscapeNpc#isAlliedTo} 的双向友军语义）。
 *
 * <p>{@code isAlliedTo} 覆盖只影响 Goety / 铁魔法这两个尊重 vanilla 中立钩子的模组，让它们在源头
 * 不索敌、不伤害本殖民地 NPC；但配置（{@code npc.friendlyFireProtection}）要覆盖**一切来源**——
 * 原版近战、箭矢、其它 mod、以及任何不走 {@code isAlliedTo} 的施法。此 handler 在
 * {@code LivingIncomingDamageEvent} 统一拦截：伤害经弹射物/召唤物/驯养宠物链解析回真实攻击者，
 * 仅当攻击者是玩家且其所属殖民地 == NPC 殖民地时取消（自家殖民地不误伤；其他殖民地仍可被误伤，
 * 与 PvP 的「跨殖民地敌对」同向）。
 *
 * <p>只改伤害结算、不改伤害来源/仇恨，因此 {@code SelfDefenseHandler}（记仇）与
 * {@code NpcSpellPowerHandler}（NPC 来源倍率、反过来处理 NPC→外部的友伤）互不影响；被取消的
 * 伤害本就来自玩家（自家殖民地），NPC 不会记仇 (isRetaliationTarget(player)==false)。
 */
public final class NpcFriendlyFireHandler {
    private NpcFriendlyFireHandler() {}

    @SubscribeEvent
    public static void onLivingDamage(LivingIncomingDamageEvent event) {
        if (!Config.NPC_FRIENDLY_FIRE_PROTECTION.get()) return;
        if (event.getEntity().level().isClientSide) return;
        if (!(event.getEntity() instanceof WandscapeNpc npc)) return;
        if (!npc.isColonyNpc()) return;

        Player attacker = playerAttacker(event.getSource());
        if (attacker == null) return;

        ColonyApi api = WandscapeApis.getColonyApiSilently();
        if (api == null) return;
        // 只豁免「该玩家拥有」的殖民地 NPC；无殖民地玩家 → null → 不豁免（可误伤他人殖民地）。
        UUID playerColony = api.getColonyByFounder(attacker.getUUID());
        if (playerColony == null || !playerColony.equals(npc.colonyId)) return;

        event.setCanceled(true);
    }

    /**
     * 伤害源是否最终源自玩家：从 {@code DamageSource.getEntity()} 经弹射物→发射者、召唤物→召唤者
     * （铁魔法 {@code IMagicSummon} / 诡厄 {@code IOwned}）、驯养宠物→主人 逐级向上爬链，命中玩家即
     * 返回；爬不到玩家（敌对生物直接攻击、僵尸弓）返回 null。用 {@code IdentityHashMap} 防环。
     */
    private static Player playerAttacker(DamageSource source) {
        Entity cur = source.getEntity();
        Set<Entity> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        while (cur != null && seen.add(cur)) {
            if (cur instanceof Player p) return p;
            Entity owner = null;
            if (cur instanceof Projectile proj) owner = proj.getOwner();
            if (owner == null && IronSpellsCompat.isLoaded()) owner = IronSpellsCompat.getSummoner(cur);
            if (owner == null && GoetyCompat.isLoaded()) owner = GoetyCompat.getMasterOwner(cur);
            if (owner == null && cur instanceof OwnableEntity own) owner = own.getOwner();
            cur = owner;
        }
        return null;
    }
}
