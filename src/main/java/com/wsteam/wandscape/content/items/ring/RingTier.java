package com.wsteam.wandscape.content.items.ring;

/**
 * 盟誓戒指档位定义。
 *
 * <p>同一玩家的所有戒指共享同一固定槽存储空间；档位决定该戒指可存取的槽位前缀数量。
 * {@code requiredColonyLevel} 是未来合成配方的解锁门槛，当前阶段不做运行时校验
 * （创造栏即可拿到三档），仅作为数据留档。
 */
public enum RingTier {

    /** 低级：可存取第一个法师（槽 0）。 */
    LOW(1, 1),
    /** 中级：可存取前两个法师（槽 0~1）。 */
    MID(2, 10),
    /** 高级：可存取全部四个法师（槽 0~3）。 */
    HIGH(4, 20);

    private final int capacity;
    private final int requiredColonyLevel;

    RingTier(int capacity, int requiredColonyLevel) {
        this.capacity = capacity;
        this.requiredColonyLevel = requiredColonyLevel;
    }

    /** 本档位可存取的固定槽数量（从槽 0 起）。 */
    public int capacity() {
        return capacity;
    }

    /** 解除本档位所需的殖民地等级（未来配方解锁用，当前不校验）。 */
    public int requiredColonyLevel() {
        return requiredColonyLevel;
    }
}