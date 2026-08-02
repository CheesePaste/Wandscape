package com.wsteam.wandscape.core.component;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.wsteam.wandscape.op.api.AtomicOp;
import com.wsteam.wandscape.task.runtime.NpcTaskPackage;
import com.wsteam.wandscape.task.runtime.TaskSequence;

/**
 * 自防御抢占依赖的核心队列机制：{@code suspendCurrent} 暂停当前包 → 注入自防御包 →
 * {@code finishCurrentPackage} 自动 {@code resumeLatest} 恢复挂起包且 stepIndex 不丢。
 */
class NpcTaskQueuePreemptionTest {

    private static final AtomicOp OP_A = new AtomicOp.EmitEventOp("a", Map.of());
    private static final AtomicOp OP_B = new AtomicOp.EmitEventOp("b", Map.of());
    private static final AtomicOp OP_C = new AtomicOp.EmitEventOp("c", Map.of());
    private static final int SELF_DEFENSE_PRIORITY = 90;

    private static NpcTaskPackage defensePkg() {
        return NpcTaskPackage.system("self_defense",
                new AtomicOp.SelfDefenseOp(12, "arcane_hexagram", 0xFFA8E0FF), null, SELF_DEFENSE_PRIORITY);
    }

    @Test
    void preemptResumesSuspendedPackageAtSavedStep() {
        NpcTaskQueue queue = new NpcTaskQueue();
        queue.startPackage(NpcTaskPackage.of("build", TaskSequence.of("Build", OP_A, OP_B, OP_C), null, 10));
        queue.advanceStep(); // 0→1
        queue.advanceStep(); // 1→2（下一步是 C）

        // 自防御抢占：暂停当前包
        queue.suspendCurrent(100);
        assertTrue(queue.hasSuspended());
        assertNull(queue.currentPackage());

        // 注入自防御包（单步）
        queue.startPackage(defensePkg());
        assertEquals("self_defense", queue.currentPackage().source());

        // 自防御完成 → 自动恢复挂起的建造包，stepIndex 回到 2、下一步仍是 C
        queue.finishCurrentPackage();
        assertNotNull(queue.currentPackage());
        assertEquals("build", queue.currentPackage().source());
        assertEquals(2, queue.stepIndex());
        assertEquals(OP_C, queue.peekCurrentOp());
    }

    @Test
    void suspensionStackFullPreventsOverwrite() {
        NpcTaskQueue queue = new NpcTaskQueue();
        // 压满挂起栈（MAX_SUSPENSION_DEPTH=3）
        for (int i = 0; i < 3; i++) {
            queue.startPackage(NpcTaskPackage.of("p" + i, TaskSequence.of("P" + i, OP_A), null, 1));
            queue.suspendCurrent(100);
        }
        // 栈满后再挂起 → 返回 null；调用方必须跳过，不能 startPackage 覆盖当前包
        queue.startPackage(NpcTaskPackage.of("p3", TaskSequence.of("P3", OP_A), null, 1));
        assertNull(queue.suspendCurrent(100));
        assertNotNull(queue.currentPackage());
        assertEquals("p3", queue.currentPackage().source());
    }

    @Test
    void preemptIdleNpcThenReturnToIdle() {
        NpcTaskQueue queue = new NpcTaskQueue();
        // 空闲 NPC：无当前包 → suspendCurrent 返回 null 但不改状态，可直接 startPackage
        assertNull(queue.suspendCurrent(100));
        queue.startPackage(defensePkg());
        queue.finishCurrentPackage();
        assertTrue(queue.isIdle());
    }
}
