package com.wsteam.wandscape.core.component;

import java.util.Map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MagicStateTest {

    private static final int MAX_MANA = 200;

    private MagicState full() {
        MagicState s = new MagicState();
        s.setMana(MAX_MANA);
        return s;
    }

    @Test
    void perMagicCooldownIndependent() {
        MagicState s = full();
        // 光束 CD 40，传送 CD 300，独立互不影响（lockDuration=0 隔离互斥锁，只测 CD）
        assertTrue(s.tryCast("beam", 40, 50, 0, 1f));
        assertTrue(s.tryCast("teleport", 300, 30, 0, 1f));
        // 同一魔法 CD 内不可再施
        assertFalse(s.tryCast("beam", 40, 50, 0, 1f));
        assertFalse(s.tryCast("teleport", 300, 30, 0, 1f));
        // 推进 39 tick 后 beam 冷却仍剩 1
        for (int i = 0; i < 39; i++) s.tickRegen(MAX_MANA, 10);
        assertEquals(1, s.getCooldown("beam"));
        assertFalse(s.tryCast("beam", 40, 50, 0, 1f));
        // 再推 1 tick：beam 冷却清空，可再施；传送仍冷却
        s.tickRegen(MAX_MANA, 10);
        assertEquals(0, s.getCooldown("beam"));
        assertTrue(s.tryCast("beam", 40, 50, 0, 1f));
        assertFalse(s.tryCast("teleport", 300, 30, 0, 1f));
    }

    @Test
    void castingLockBlocksAllMagics() {
        MagicState s = full();
        // 光束施法全程 160 tick，期间任何魔法不可施
        assertTrue(s.tryCast("beam", 40, 50, 160, 1f));
        assertTrue(s.getLockTicks() > 0);
        assertFalse(s.tryCast("teleport", 300, 30, 1, 1f));
        assertFalse(s.tryCast("beam", 40, 50, 1, 1f));
        // 推进 160 tick：锁释放；但 CD 在锁期间冻结，锁释放后才开始倒计时
        for (int i = 0; i < 160; i++) s.tickRegen(MAX_MANA, 10);
        assertEquals(0, s.getLockTicks());
        assertEquals(40, s.getCooldown("beam"), "CD 在锁期间冻结，锁释放后才起算");
        assertFalse(s.tryCast("beam", 40, 50, 1, 1f), "锁释放后 CD 未清仍不可施");
        // 再推进 40 tick：beam CD 清空可再施；teleport CD 300 仍未过
        for (int i = 0; i < 40; i++) s.tickRegen(MAX_MANA, 10);
        assertTrue(s.tryCast("beam", 40, 50, 1, 1f));
        assertFalse(s.tryCast("teleport", 300, 30, 1, 1f));
    }

    @Test
    void manaGatesCasting() {
        MagicState s = full();
        // 满蓝：光束 50、传送 30，剩余 120
        assertTrue(s.tryCast("beam", 40, 50, 0, 1f));
        assertTrue(s.tryCast("teleport", 300, 30, 0, 1f));
        assertEquals(120f, s.getMana());
        // 蓝不足：拒绝且不扣蓝（teleport 冷却未过，但此处主因是蓝不足）
        s.setMana(10f);
        assertFalse(s.tryCast("teleport", 300, 30, 0, 1f));
        assertEquals(10f, s.getMana());
        // 换一个尚无冷却的魔法，验证「蓝刚好够就成功」
        s.setMana(30f);
        assertTrue(s.tryCast("rain", 100, 30, 0, 1f));
        assertEquals(0f, s.getMana());
    }

    @Test
    void regenEveryIntervalCappedAtMax() {
        MagicState s = full();
        s.setMana(150f);
        for (int i = 0; i < 9; i++) s.tickRegen(MAX_MANA, 10);
        assertEquals(150f, s.getMana()); // 9 tick 未到 10
        s.tickRegen(MAX_MANA, 10);
        assertEquals(151f, s.getMana());
        // 直到封顶 200（151→200 需 49 点，600 tick 足够）
        for (int i = 0; i < 600; i++) s.tickRegen(MAX_MANA, 10);
        assertEquals(200f, s.getMana());
        // 满蓝时累计清零
        assertEquals(0, s.getManaRegenAccum());
    }

    @Test
    void spellSpeedShortensCooldown() {
        MagicState s = full();
        // SPELL_SPEED=2 → 40/2=20，向上取整
        assertTrue(s.tryCast("beam", 40, 50, 0, 2f));
        assertEquals(20, s.getCooldown("beam"));
        // SPELL_SPEED<=1 不缩短
        assertTrue(s.tryCast("teleport", 300, 30, 0, 0.8f));
        assertEquals(300, s.getCooldown("teleport"));
    }

    @Test
    void loadRestoresPersistedState() {
        MagicState s = full();
        s.tryCast("beam", 40, 50, 10, 1f);
        s.tryCast("teleport", 300, 30, 1, 1f);
        int mana = (int) s.getMana();
        int accum = 3;
        s.setManaRegenAccum(accum);
        int lock = s.getLockTicks();
        var cds = Map.of("beam", s.getCooldown("beam"), "teleport", s.getCooldown("teleport"));

        MagicState t = new MagicState();
        t.load(mana, accum, lock, true, cds);
        assertEquals(mana, (int) t.getMana());
        assertEquals(accum, t.getManaRegenAccum());
        assertEquals(lock, t.getLockTicks());
        assertTrue(t.isManaSeeded());
        assertEquals(cds.get("beam"), t.getCooldown("beam"));
        assertEquals(cds.get("teleport"), t.getCooldown("teleport"));
    }

    @Test
    void altarCastDeductsManaAndLocksWithoutPerMagicCooldown() {
        MagicState s = full();
        // 祭坛施法：扣蓝 80、占锁 160，但不设任何每魔法 CD（祭坛 CD 独立存放）
        assertTrue(s.tryAltarCast(80, 160));
        assertEquals(120f, s.getMana());
        assertTrue(s.getLockTicks() > 0);
        assertEquals(0, s.getCooldown("revive"), "祭坛施法不设置 NPC 每魔法 CD");
        // 锁占用期间任何魔法（含祭坛）不可施
        assertFalse(s.tryCast("beam", 40, 50, 1, 1f));
        assertFalse(s.tryAltarCast(10, 1));
        // 推进 160 tick：锁释放，可再祭坛施法
        for (int i = 0; i < 160; i++) s.tickRegen(MAX_MANA, 10);
        assertEquals(0, s.getLockTicks());
        assertTrue(s.tryAltarCast(80, 160));
    }

    @Test
    void altarCastRejectsInsufficientManaWithoutDeducting() {
        MagicState s = full();
        s.setMana(50f);
        assertFalse(s.tryAltarCast(80, 160), "魔力不足应拒绝");
        assertEquals(50f, s.getMana(), "拒绝时不能扣蓝");
        assertEquals(0, s.getLockTicks(), "拒绝时不能占锁");
    }

    @Test
    void freeCastModeIgnoresCooldownAndManaCost() {
        try {
            MagicState.setFreeCast(true);
            assertTrue(MagicState.isFreeCast());

            MagicState s = new MagicState();
            s.setMana(0f); // 0 蓝

            // 0 蓝、0 冷却限制下仍可释放，且不扣蓝
            assertTrue(s.tryCast("black_hole", 2400, 300, 0, 1f));
            assertEquals(0f, s.getMana(), "测试模式下不扣蓝");
            assertEquals(0, s.getCooldown("black_hole"), "测试模式下不设 CD");
            assertTrue(s.canCast("black_hole"), "测试模式下始终可施放");

            // 祭坛施法同样免蓝
            assertTrue(s.tryAltarCast(100, 0));
            assertEquals(0f, s.getMana());
        } finally {
            MagicState.setFreeCast(false);
            assertFalse(MagicState.isFreeCast());
        }
    }
}
