package com.wsteam.wandscape.core.component;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.wsteam.wandscape.op.api.AtomicOp;
import com.wsteam.wandscape.task.runtime.NpcTaskPackage;
import com.wsteam.wandscape.task.runtime.TaskSequence;

/**
 * 跟随模式队列门控：{@code dropGlobalPackages} 丢弃所有 {@code global:*} 包
 * （当前/pending/挂起栈），保留 {@code self_defense} 等个人包。
 */
class NpcTaskQueueFollowTest {

    private static final AtomicOp OP = new AtomicOp.EmitEventOp("a", Map.of());
    private static final int SELF_DEFENSE_PRIORITY = 90;

    @Test
    void dropGlobalPackages_keepsSelfDefenseCurrent() {
        NpcTaskQueue queue = new NpcTaskQueue();
        // 全局包被自防御抢断 → 挂起栈: global:1；当前: self_defense；pending: global:2
        queue.startPackage(NpcTaskPackage.of("global:1", TaskSequence.of("G1", OP), null, 10));
        queue.suspendCurrent(0);
        queue.startPackage(NpcTaskPackage.system("self_defense",
                new AtomicOp.SelfDefenseOp(12), null, SELF_DEFENSE_PRIORITY));
        queue.enqueueNormal(NpcTaskPackage.of("global:2", TaskSequence.of("G2", OP), null, 10));

        assertTrue(queue.hasGlobalPackage());
        queue.dropGlobalPackages();

        assertFalse(queue.hasGlobalPackage(), "跟随后不再持有任何 global 包");
        assertNotNull(queue.currentPackage());
        assertEquals("self_defense", queue.currentPackage().source(), "自防御个人包保留");
        assertFalse(queue.hasSuspended(), "挂起的 global 包被丢弃");
        assertEquals(0, queue.pendingSize(), "pending 中的 global 包被丢弃");
    }

    @Test
    void dropGlobalPackages_clearsCurrentGlobal() {
        NpcTaskQueue queue = new NpcTaskQueue();
        queue.startPackage(NpcTaskPackage.of("global:9", TaskSequence.of("G9", OP), null, 10));
        assertTrue(queue.hasGlobalPackage());
        queue.dropGlobalPackages();
        assertNull(queue.currentPackage());
        assertFalse(queue.hasGlobalPackage());
        assertTrue(queue.isIdle());
    }
}
