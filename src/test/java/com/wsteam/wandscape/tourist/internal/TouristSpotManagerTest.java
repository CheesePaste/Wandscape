package com.wsteam.wandscape.tourist.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class TouristSpotManagerTest {

    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-000000000003");

    /** 同 spot 入队按先后排序：队首下标 0，越晚越靠后。 */
    @Test
    void joinQueueAssignsFifoOrder() {
        TouristSpotManager m = new TouristSpotManager();
        assertEquals(0, m.joinQueue(B, 0, A));
        assertEquals(1, m.joinQueue(B, 0, B));
        assertEquals(2, m.joinQueue(B, 0, C));
        assertEquals(3, m.queueSize(B, 0));
        assertEquals(0, m.queuePosition(B, 0, A));
        assertEquals(1, m.queuePosition(B, 0, B));
        assertEquals(2, m.queuePosition(B, 0, C));
    }

    /** 同一游客重复入队是幂等的：不重复占位，返回其当前队序（0 = 队首不变）。 */
    @Test
    void joinQueueIsIdempotentForSameId() {
        TouristSpotManager m = new TouristSpotManager();
        m.joinQueue(B, 0, A);
        m.joinQueue(B, 0, B);
        assertEquals(0, m.joinQueue(B, 0, A));
        assertEquals(2, m.queueSize(B, 0));
    }

    /** 队首离队 → 后续游客队序整体前移 1（站位随之前移）。 */
    @Test
    void leavingFrontShiftsEveryoneForward() {
        TouristSpotManager m = new TouristSpotManager();
        m.joinQueue(B, 0, A);
        m.joinQueue(B, 0, B);
        m.joinQueue(B, 0, C);
        m.leaveAllQueues(B, A);
        assertEquals(0, m.queuePosition(B, 0, B));
        assertEquals(1, m.queuePosition(B, 0, C));
        assertEquals(2, m.queueSize(B, 0));
    }

    /** 中间离队 → 其后的前移，其前的保持不变。 */
    @Test
    void leavingMiddleShiftsOnlyThoseBehind() {
        TouristSpotManager m = new TouristSpotManager();
        m.joinQueue(B, 0, A);
        m.joinQueue(B, 0, B);
        m.joinQueue(B, 0, C);
        m.leaveAllQueues(B, B);
        assertEquals(0, m.queuePosition(B, 0, A));
        assertEquals(1, m.queuePosition(B, 0, C));
    }

    /** 不在队中的离队是幂等 no-op；队清空后条目被移除。 */
    @Test
    void leaveQueueIsIdempotentAndCleansEmpty() {
        TouristSpotManager m = new TouristSpotManager();
        m.leaveAllQueues(B, A); // 不在队中，无异常
        m.joinQueue(B, 0, A);
        m.leaveAllQueues(B, A);
        assertEquals(-1, m.queuePosition(B, 0, A));
        assertEquals(0, m.queueSize(B, 0));
        // 空队再入队应从头开始（条目不残留）
        assertEquals(0, m.joinQueue(B, 0, B));
    }

    /** 不同 spot 的队互不干扰（每交互点一队）。 */
    @Test
    void queuesArePerSpot() {
        TouristSpotManager m = new TouristSpotManager();
        m.joinQueue(B, 0, A);
        m.joinQueue(B, 0, B);
        m.joinQueue(B, 1, A); // 同一游客可排另一个 spot 的队
        assertEquals(0, m.queuePosition(B, 0, A));
        assertEquals(0, m.queuePosition(B, 1, A));
        assertEquals(2, m.queueSize(B, 0));
        assertEquals(1, m.queueSize(B, 1));
        // 只清 0 号队，1 号队不受影响
        m.leaveAllQueues(B, A);
        assertEquals(1, m.queueSize(B, 0));
        assertEquals(0, m.queueSize(B, 1));
    }

    /** 均匀分布：新游客排到队最短的 spot（并列取最小下标）。 */
    @Test
    void shortestQueueSpotBalancesLoad() {
        TouristSpotManager m = new TouristSpotManager();
        // 三个 spot 都空 → 选 0
        assertEquals(0, m.shortestQueueSpot(B, 3));
        m.joinQueue(B, 0, A);
        m.joinQueue(B, 0, B);
        m.joinQueue(B, 2, C);
        // spot0=2 人, spot1=0 人, spot2=1 人 → 选 1
        assertEquals(1, m.shortestQueueSpot(B, 3));
    }

    /** 指定 spot 认领：只能认领空位，已被占返回 -1。 */
    @Test
    void claimAtClaimsOnlyFreeSpot() {
        TouristSpotManager m = new TouristSpotManager();
        assertEquals(1, m.claimAt(B, 1, 3));
        assertEquals(-1, m.claimAt(B, 1, 3)); // 已被占
        assertEquals(2, m.claimAt(B, 2, 3));
        m.release(B, 1);
        assertEquals(1, m.claimAt(B, 1, 3)); // 释放后可再认领
    }

    /** 在队中的游客与占位无耦合：排队与 spot 占用互不影响。 */
    @Test
    void queueAndSpotOccupancyAreIndependent() {
        TouristSpotManager m = new TouristSpotManager();
        assertEquals(0, m.claim(B, 2));           // 占 spot 0
        assertEquals(-1, m.queuePosition(B, 0, A)); // 未入队 → 队序 -1
        assertEquals(0, m.joinQueue(B, 1, A));     // 入 spot1 的队
        m.release(B, 0);                          // 释放只影响占位
        assertEquals(2, m.freeSpotCount(B, 2));   // 占位恢复
        assertEquals(0, m.queuePosition(B, 1, A)); // 队列不受占位影响
    }
}
