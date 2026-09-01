package com.wsteam.wandscape.compat.ironspellbooks;
import com.wsteam.wandscape.content.npc.component.MagicState;
import com.wsteam.wandscape.content.task.ecs.World;

import com.wsteam.wandscape.content.npc.attributes.NpcAttributes.AttributeType;
import com.wsteam.wandscape.content.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.foundation.log.Log;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import io.redspace.ironsspellbooks.api.spells.CastType;
import io.redspace.ironsspellbooks.capabilities.magic.SyncedSpellData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 铁魔法施法执行器：处理瞬发、长蓄力、持续引导法术的状态与生命周期。
 */
public final class IronSpellsCaster {

    private static final String TAG = "IronSpellsCaster";

    private static final class ActiveCast {
        final ServerLevel level;
        final WandscapeNpc npc;
        @Nullable
        final LivingEntity target;
        final AbstractSpell spell;
        final int spellLevel;
        final CastType castType;
        final MagicData magicData;
        int remainingTicks;

        ActiveCast(ServerLevel level, WandscapeNpc npc, @Nullable LivingEntity target,
                   AbstractSpell spell, int spellLevel, int remainingTicks,
                   CastType castType, MagicData magicData) {
            this.level = level;
            this.npc = npc;
            this.target = target;
            this.spell = spell;
            this.spellLevel = spellLevel;
            this.remainingTicks = remainingTicks;
            this.castType = castType;
            this.magicData = magicData;
        }
    }

    private static final List<ActiveCast> ACTIVE_CASTS = new ArrayList<>();

    private IronSpellsCaster() {}

    /**
     * 获取并确保 NPC 的 MagicData 具备有效的 SyncedSpellData 实例（避免 initiateCast NPE）。
     */
    private static MagicData getOrCreateMagicData(WandscapeNpc npc) {
        MagicData magicData = MagicData.getPlayerMagicData(npc);
        if (magicData.getSyncedData() == null) {
            magicData.setSyncedData(new SyncedSpellData(npc));
        }
        return magicData;
    }

    /**
     * 为 NPC 施放铁魔法。
     */
    public static boolean cast(ServerLevel level, WandscapeNpc npc, @Nullable LivingEntity target,
                               String spellId, int spellLevel) {
        if (!IronSpellsCompat.isLoaded()) return false;
        AbstractSpell spell = SpellRegistry.getSpell(spellId);
        if (spell == null || spell == SpellRegistry.none() || !spell.isEnabled()) {
            return false;
        }

        // 蓝耗 1:1：铁魔法蓝耗直接对等 NPC 魔力池（2026-08-26 用户要求），不再按 0.25/0.10 缩放 / 下限钳制。
        int manaCost = spell.getManaCost(spellLevel);
        // 冷却：getSpellCooldown() 已返回 tick（COOLDOWN_IN_SECONDS × 20），直接用；SPELL_SPEED 在 MagicState 缩短。
        int baseCooldown = spell.getSpellCooldown() > 0 ? spell.getSpellCooldown() : 40;
        CastType castType = spell.getCastType();

        if (target != null && target.isAlive()) {
            npc.faceTarget(target.getEyePosition());
        }

        float spellSpeed = Math.max(0.1f, npc.getEffectiveAttribute(AttributeType.SPELL_SPEED));

        if (castType == CastType.INSTANT || castType == CastType.NONE) {
            int lockTicks = Math.max(10, (int) Math.ceil(10.0 / spellSpeed));
            if (!npc.tryCastSpell(spellId, baseCooldown, manaCost, lockTicks)) {
                return false;
            }

            npc.swing(net.minecraft.world.InteractionHand.MAIN_HAND, true);
            MagicData magicData = getOrCreateMagicData(npc);
            magicData.initiateCast(spell, spellLevel, 0, CastSource.MOB, "mainhand");
            spell.onCast(level, spellLevel, npc, CastSource.MOB, magicData);
            spell.onServerCastComplete(level, spellLevel, npc, magicData, false);
            spell.getCastFinishSound().ifPresent(s -> level.playSound(null, npc.getX(), npc.getY(), npc.getZ(),
                    s, SoundSource.NEUTRAL, 1.0f, 1.0f));

            Log.info(TAG, "NPC {} cast instant iron spell '{}' Lv.{}",
                    npc.getUUID().toString().substring(0, 8), spellId, spellLevel);
            return true;
        } else {
            // LONG / CONTINUOUS 蓄力或引导：开始即一次性扣全量（铁魔法自身无按秒扣蓝机制，
            // 与瞬发/蓄力一致，2026-08-26 用户要求不按 tick 扣）
            int rawCastTime = spell.getCastTime(spellLevel);
            int lockTicks = Math.max(10, (int) Math.ceil(rawCastTime / spellSpeed));
            if (!npc.tryCastSpell(spellId, baseCooldown, manaCost, lockTicks)) {
                return false;
            }

            MagicData magicData = getOrCreateMagicData(npc);
            magicData.initiateCast(spell, spellLevel, lockTicks, CastSource.MOB, "mainhand");
            spell.onServerPreCast(level, spellLevel, npc, magicData);
            spell.getCastStartSound().ifPresent(s -> level.playSound(null, npc.getX(), npc.getY(), npc.getZ(),
                    s, SoundSource.NEUTRAL, 1.0f, 1.0f));

            ACTIVE_CASTS.add(new ActiveCast(level, npc, target, spell, spellLevel, lockTicks, castType, magicData));
            Log.info(TAG, "NPC {} began channeling iron spell '{}' Lv.{} (lockTicks={})",
                    npc.getUUID().toString().substring(0, 8), spellId, spellLevel, lockTicks);
            return true;
        }
    }

    /**
     * 每 server tick 调用：推进持续施法与长蓄力法术。
     */
    public static void tickAll() {
        if (!IronSpellsCompat.isLoaded() || ACTIVE_CASTS.isEmpty()) return;

        Iterator<ActiveCast> it = ACTIVE_CASTS.iterator();
        while (it.hasNext()) {
            ActiveCast cast = it.next();
            if (cast.npc.isRemoved() || !cast.npc.isAlive()) {
                it.remove();
                continue;
            }

            if (cast.target != null && cast.target.isAlive() && !cast.target.isRemoved()) {
                cast.npc.faceTarget(cast.target.getEyePosition());
            }

            cast.magicData.handleCastDuration();

            if (cast.castType == CastType.CONTINUOUS) {
                cast.spell.onServerCastTick(cast.level, cast.spellLevel, cast.npc, cast.magicData);
                // 持续引导按周期触发每跳效果
                if (cast.remainingTicks % 10 == 0) {
                    cast.spell.onCast(cast.level, cast.spellLevel, cast.npc, CastSource.MOB, cast.magicData);
                }
            }

            cast.remainingTicks--;
            if (cast.remainingTicks <= 0) {
                if (cast.target != null && cast.target.isAlive() && !cast.target.isRemoved()) {
                    cast.npc.faceTarget(cast.target.getEyePosition());
                }

                cast.npc.swing(net.minecraft.world.InteractionHand.MAIN_HAND, true);

                // 蓄力法术在蓄满时触发落地效果（如黑洞生成、火球发射）
                if (cast.castType == CastType.LONG) {
                    cast.spell.onCast(cast.level, cast.spellLevel, cast.npc, CastSource.MOB, cast.magicData);
                }

                cast.spell.onServerCastComplete(cast.level, cast.spellLevel, cast.npc, cast.magicData, false);
                cast.spell.getCastFinishSound().ifPresent(s -> cast.level.playSound(null,
                        cast.npc.getX(), cast.npc.getY(), cast.npc.getZ(), s, SoundSource.NEUTRAL, 1.0f, 1.0f));
                it.remove();
                Log.info(TAG, "NPC {} completed iron spell '{}' Lv.{}",
                        cast.npc.getUUID().toString().substring(0, 8), cast.spell.getSpellId(), cast.spellLevel);
            }
        }
    }
}
