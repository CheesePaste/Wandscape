# NPC 系统

文档编号：NEW-07
版本：1.0
状态：NPC 实体 + 属性 + 个人魔力 + 死亡/坟墓 + 房屋绑定（**完整设计目标**）
依赖：01-shared-api

> **⚠️ 实现参考**：阶段 2 的实际实现规格见 `docs/22-npc-mc-adapter.md`（V1 最小可扩展版）。22 是 07 的子集——先跑通引擎闭环，后续阶段逐步对齐 07 的完整功能。差异对照表见 22 §八。

---

## 一、职责边界

- 注册 NPC 实体（`WandscapeVillager`）
- 管理 NPC 属性：生命值、个人魔力池、法术强度、恢复速率
- NPC 魔力自然恢复 + 房屋加速恢复
- NPC 死亡 → 物品以掉落物形式散落在死亡位置
- NPC 与法杖的关联（背包 = 能力来源）
- 后续阶段将实现坟墓系统（NPC 物品转入坟墓保管，永久存在直到玩家手动移除）

**不包含：**
- NPC 如何接取/执行任务（任务系统负责）
- NPC 如何被招募（酒馆模块负责）
- 复活仪式如何执行（仪式祭坛模块负责）
- NPC AI 行为树（仅提供状态机，行为由任务系统驱动）

---

## 二、NPC 实体

### 2.1 注册

```java
public static final DeferredEntityType<WandscapeNpc> NPC =
    ENTITIES.register("wandscape_npc",
        () -> EntityType.Builder.of(WandscapeNpc::new, MobCategory.CREATURE)
            .sized(0.6f, 1.8f)
            .build("wandscape_npc"));
```

NPC 移动分为寻路步行和传送两种方式。**传送本身是原子操作 D**：NPC 生成私有任务 `OperationD(ritualId="self_teleport", ...)`，由原子执行器统一处理（魔力扣除、粒子特效、位置传送）。不硬编码消耗，消耗定义在 `data/wandscape/rituals/self_teleport.json` 的 `mana_cost` 字段。

### 移动决策

```
NPC 需要前往目标位置
    │
    ├→ 距离 < 64 方块？
    │       ├→ 是 → 尝试寻路 (PathNavigation)
    │       │       ├→ 寻路成功 → 沿路步行移动
    │       │       │       └→ 每 3 秒检查进展 → 连续 3 次无进展（约 10 秒卡死）
    │       │       │           └→ 入队私有 self_teleport 任务
    │       │       └→ 寻路失败（无路径/目标不可达）
    │       │               └→ 入队私有 self_teleport 任务
    │       │
    │       └→ 否（距离 ≥ 64）→ 入队私有 self_teleport 任务
    │
    └→ self_teleport 任务执行（走标准 Operation D 路径）
            ├→ 原子执行器检查魔力（来自 ritual JSON 的 mana_cost）
            ├→ 魔力不足 → 任务挂起，NPC 原地等恢复 → 恢复后继续执行（不中断任务）
            └→ 魔力充足 → 扣魔力 → 粒子特效 → 传送至目标
```

### 2.2 卡死判定

NPC 开始寻路步行后，每 `STUCK_CHECK_INTERVAL_TICKS`（3 秒）检查：
1. 当前坐标与 3 秒前记录的坐标比较
2. 移动距离 < `STUCK_MIN_MOVE_DISTANCE`（2 格）→ 无进展
3. 连续 `STUCK_MAX_RETRIES`（3 次）无进展 → 判定卡死
4. 卡死后入队私有 `self_teleport` 任务（同上，走标准 Operation D 路径）

卡死常见原因：被方块围困、路径被生物堵死、掉入坑中反复攀爬、门被关闭等。

### 2.3 外观

- 统一使用"法师村民"模型
- 手持法杖显示在右手
- 空闲时收起法杖，工作时举起
- 无职业着装差异（法杖即能力，外表统一）

---

## 三、属性

### 3.1 属性定义

| 属性 | 默认值 | 说明 |
|------|--------|------|
| 最大生命值 | 40 | 归零死亡 |
| 当前生命值 | 40 | — |
| 最大魔力值 | 100 | 施法消耗 |
| 当前魔力值 | 100 | 自然恢复 |
| 法术强度 | 1 | 影响施法效果 |
| 魔力恢复速率 | 2 / tick | 基础恢复 |

### 3.2 魔力恢复

```java
// 每 tick
public void tickManaRegen() {
    float multiplier = 1.0f;
    if (isInHouse()) {
        multiplier = HOUSE_MANA_REGEN_MULTIPLIER; // 3.0x
    }
    currentMana = Math.min(maxMana, currentMana + manaRegenRate * multiplier);
}
```

### 3.3 状态

| 状态 | 说明 |
|------|------|
| 空闲 | 无任务，在房屋或殖民地内活动 |
| 工作中 | 正在执行全局或私有任务 |
| 卡死 | 被调度器判定卡死，等待自动重置 |
| 死亡 | 生命值归零，坟墓已生成 |

---

## 四、死亡

### 4.1 死亡流程

NPC 生命值 → 0：
1. NPC 实体移除
2. 背包内所有物品以掉落物形式散落在死亡位置
3. 触发 `NpcDiedEvent`（携带死亡位置和 NPC 数据）
4. 玩家可在掉落物消失前拾取装备

> **后续阶段**：将实现坟墓系统——NPC 死亡后生成坟墓方块实体保管物品，坟墓永久存在直到玩家手动移除。坟墓的移除与复活无关。

### 4.2 复活

复活由仪式祭坛模块通过操作 D 完成。复活后：
- NPC 重生在祭坛旁
- 生命值 + 魔力值满
- **不携带任何装备和法杖**（需玩家重新配发）
- **默认 `ritual:1` 仍然生效**（与装备无关，来自能力并集计算时自动合并），复活后 NPC 仍可执行物资传送、魔力池充能等基础操作
- 触发 `NpcResurrectedEvent`

---

## 五、NPC 与法杖

NPC 的能力集 = 背包内所有法杖的 NBT 行为标签并集 + **默认 `ritual:1`**（无论是否持有法杖，来自 `computeAbilities` 自动合并）。

此默认 `ritual:1` 保证所有 NPC（包括复活后无装备的 NPC）始终可执行基础物流操作：物资传送、魔力池充能/抽取等。

NPC 接取任务后，自动以最匹配任务需求的那把法杖作为主手。

### 5.1 背包管理

玩家可通过管理面板查看 NPC 背包（法杖和物品），但不能通过管理面板直接放取物品（需亲自找到 NPC 交互）。

---

## 六、房屋绑定

每个 NPC 可绑定至一座房屋建筑：
- 绑定后，NPC 空闲时返回该房屋
- 在房屋内魔力恢复速度 ×3
- 未绑定的 NPC 空闲时在殖民地中心区域活动（魔力恢复慢）
- 绑定操作通过管理面板完成

---

## 七、持久化

NPC 数据随实体保存。殖民地卸载时 NPC 数据不丢失。

```java
@Override
public void addAdditionalSaveData(CompoundTag tag) {
    tag.putUUID("colonyId", colonyId);
    tag.putInt("currentMana", currentMana);
    tag.putUUID("assignedHouseId", assignedHouseId);
    // 背包物品
    tag.put("inventory", inventory.save());
}
```

---

## 八、独立测试方案

### 单元测试

1. **属性计算**：法术强度影响施法时间的公式正确
2. **魔力恢复**：基础恢复 vs 房屋加速恢复计算正确
3. **能力并集**：不同法杖组合得到正确的 AbilitySet（含默认 ritual:1）

### 集成测试

1. 生成 NPC，验证属性默认值正确
2. NPC 在房屋内魔力恢复速度是否为基础 ×3
3. NPC 死亡 → 物品掉落地上 → NpcDiedEvent 触发
4. 复活后 NPC 装备清空，重生在祭坛旁
5. NPC 切换主手法杖（接取不同任务时）
