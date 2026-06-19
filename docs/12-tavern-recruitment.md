# 酒馆与招募

文档编号：NEW-12
版本：1.1
状态：酒馆建筑 + 候选人三选一 + 刷新机制
依赖：01-shared-api, 08-building-core

---

## 一、职责边界

- 酒馆建筑方块和方块实体
- 生成 3 个招募候选人（基于舒适值随机属性）
- 候选人的刷新（消耗资源）
- 招募任务入队（扣费延迟到 OperationB 执行时）
- 初始 NPC 可能自带基础法杖

**不包含：**
- NPC 实体和属性管理（07 模块负责）
- 管理面板中的招募 UI（14 模块负责）
- 舒适值如何计算（08 模块负责）

---

## 二、酒馆建筑

### 2.1 JSON 配置

```json
{
  "id": "tavern",
  "display_name": "酒馆",
  "category": "functional",
  "block_id": "wandscape:tavern",
  "comfort": 1,
  "magic": 1,
  "wonder": 2,
  "maintenance_cost": 3,
  "tavern_config": {
    "recruitment_cost": {
      "wood": 64,
      "earth": 32
    },
    "refresh_cost": {
      "wood": 16,
      "earth": 8
    },
    "recruitment_cooldown_ticks": 6000,
    "candidates_count": 3
  },
  "queue": {
    "capacity": 5,
    "task_types": ["recruitment"]
  },
  "unlock_requirement": { "min_wonder": 2 }
}
```

### 2.2 TavernBE

```java
public class TavernBE extends AbstractWandscapeBE {
    private long lastRecruitmentTick = 0;
    private int recruitmentCooldownTicks;
    private List<RecruitmentCandidate> candidates = new ArrayList<>();

    // ========== TavernApi 实现 ==========

    /** 获取候选人列表。首次调用时自动生成。 */
    public List<RecruitmentCandidate> getCandidates() {
        if (candidates.isEmpty()) {
            generateCandidates();
        }
        return List.copyOf(candidates);
    }

    /** 消耗资源刷新候选人。返回 false 表示资源不足。 */
    public boolean refreshCandidates() {
        if (!checkAndDeductRefreshCost()) return false;
        generateCandidates();
        return true;
    }

    /** 玩家选中候选人入队招募任务。不在此处扣费，扣费在 OperationB 执行时。 */
    public boolean recruitCandidate(int index) {
        if (index < 0 || index >= candidates.size()) return false;
        // 检查冷却
        if (level.getGameTime() - lastRecruitmentTick < recruitmentCooldownTicks) {
            return false;
        }
        RecruitmentCandidate c = candidates.get(index);

        // 入队招募任务（扣费延迟到 NPC 执行 OperationB 时）
        TaskTemplate recruitTask = new TaskTemplate(
            BehaviorType.RITUAL,
            1,
            List.of(
                new OperationB(this.getUUID(), "recruit",
                    Map.of("colonyId", colonyId.toString(),
                           "level", c.level(),
                           "maxHealth", c.maxHealth(),
                           "maxMana", c.maxMana(),
                           "spellPower", c.spellPower(),
                           "manaRegen", c.manaRegen(),
                           "starterWandIds", c.starterWandIds()))
            )
        );
        TaskApi.enqueueBuildingTask(this.getUUID(), recruitTask);
        lastRecruitmentTick = level.getGameTime();

        // 移除已选候选人
        candidates.remove(index);
        return true;
    }

    // ========== OperationB "recruit" 处理器 ==========

    /** 由 AtomicExecutor.executeB() 调用。执行实际的扣费 + 生成 NPC。 */
    public boolean handleRecruit(Map<String, Object> params) {
        UUID colonyId = UUID.fromString((String) params.get("colonyId"));

        // 检查并扣除招募费用
        if (!checkAndDeductRecruitmentCost(colonyId)) return false;

        // 生成 NPC
        int level = (int) params.get("level");
        int maxHealth = (int) params.get("maxHealth");
        int maxMana = (int) params.get("maxMana");
        int spellPower = (int) params.get("spellPower");
        int manaRegen = (int) params.get("manaRegen");
        @SuppressWarnings("unchecked")
        List<String> wandIds = (List<String>) params.get("starterWandIds");

        RecruitmentCandidate candidate = new RecruitmentCandidate(
            level, maxHealth, maxMana, spellPower, manaRegen, wandIds);
        NpcApi.spawnNpc(colonyId, worldPosition.above(), candidate);
        return true;
    }

    // ========== 内部方法 ==========

    private void generateCandidates() {
        int comfort = BuildingApi.getColonyComfort(colonyId);
        candidates.clear();
        for (int i = 0; i < CANDIDATES_COUNT; i++) {
            candidates.add(generateOneCandidate(comfort));
        }
    }

    private RecruitmentCandidate generateOneCandidate(int comfort) {
        int maxLevel = (comfort / 5) + 1;
        int level = random.nextInt(maxLevel) + 1;
        int maxHealth = DEFAULT_NPC_MAX_HEALTH + level * 5;
        int maxMana = DEFAULT_NPC_MAX_MANA + level * 20;
        int spellPower = DEFAULT_NPC_SPELL_POWER + random.nextInt(level);
        int manaRegen = DEFAULT_NPC_MANA_REGEN + random.nextInt(level);
        List<String> wands = rollStarterWands(comfort);
        return new RecruitmentCandidate(level, maxHealth, maxMana, spellPower, manaRegen, wands);
    }

    private boolean checkAndDeductRecruitmentCost(UUID colonyId) { /* ... */ }
    private boolean checkAndDeductRefreshCost() { /* ... */ }
}
```

---

## 三、招募规则

### 3.1 等级上限

可招募的 NPC 等级上限 = `floor(舒适值 / 5)` + 1

示例：
- 舒适值 0 → 可招募 1 级 NPC
- 舒适值 10 → 可招募 3 级 NPC
- 舒适值 25 → 可招募 6 级 NPC

NPC 等级影响其初始属性（生命值、魔力值、法术强度）。

### 3.2 候选人生成

每次刷新生成 3 个候选人，每个独立随机：
- `level = random.nextInt(maxLevel) + 1`
- 属性基于 `level` 计算，带小幅随机偏移
- 法杖基于舒适值概率获得

### 3.3 初始法杖

新招募 NPC 有一定概率自带基础法杖：

| 舒适值范围 | 自带法杖概率 |
|-----------|------------|
| 0-4 | 20% 自带 1 把随机基础法杖 |
| 5-9 | 50% 自带 1 把 |
| 10-14 | 80% 自带 1-2 把 |
| 15+ | 100% 自带 2 把 |

---

## 四、消耗

### 4.1 招募消耗

每次招募消耗元素由 `tavern_config.recruitment_cost` 定义。扣费发生在 NPC 执行 OperationB "recruit" 时，而非入队时。

如果 NPC 执行时资源不足 → 任务失败，退回全局池重新分配。

### 4.2 刷新消耗

刷新候选人消耗由 `tavern_config.refresh_cost` 定义（通常为招募费的 1/4）。扣费立即发生。资源不足时刷新按钮不可用。

---

## 五、完整流程

```
玩家打开管理面板 → 酒馆页签
        │
        ├→ 首次查看 → TavernApi.getCandidates() → 自动生成 3 个候选人
        │
        ├→ [刷新候选人] → TavernApi.refreshCandidates()
        │       ├→ 检查刷新费用 → 扣除 → 重新生成 3 人
        │       └→ 资源不足 → 拒绝
        │
        └→ [选择候选人] → TavernApi.recruitCandidate(index)
                ├→ 检查冷却
                ├→ 创建 TaskTemplate(BehaviorType.RITUAL, 1, [OperationB(tavernId, "recruit", candidateData)])
                └→ 入队酒馆队列
                        │
                        ▼
                  酒馆队列 → 全局任务池
                        │
                        ▼
                  调度器匹配空闲 NPC (ritual:1)
                        │
                        ▼
                  NPC 前往酒馆 → 执行 OperationB "recruit"
                        │
                        ▼
                  TavernBE.handleRecruit():
                    ├─ 检查并扣除招募费用
                    ├─ NpcApi.spawnNpc(colonyId, pos, candidate)
                    └─ 触发 NpcRecruitedEvent
```

---

## 六、独立测试方案

### 单元测试

1. **等级上限计算**：舒适值 0 → maxLevel=1；舒适值 12 → maxLevel=3
2. **属性范围**：生成的候选人属性在合法范围内
3. **冷却检查**：冷却期内拒绝招募
4. **候选人数量**：每次生成恰好 3 个
5. **刷新替换**：刷新后旧候选人被新列表替换
6. **扣费时机**：入队时不扣费，OperationB 执行时扣费

### 集成测试

1. 打开管理面板酒馆页签 → 显示 3 个候选人卡片
2. 点击"刷新" → 元素消耗 → 3 个新候选人
3. 选择候选人 → 任务入队 → NPC 执行 → 扣费 + 新 NPC 出现在酒馆旁
4. 执行时资源不足 → 任务失败，退回全局池
5. 舒适值不足时招募按钮灰色/提示
6. 两次连续招募之间冷却正确
