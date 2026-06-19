# 元素系统

文档编号：NEW-03
版本：1.0
状态：三层元素定义 + 元素储量接口 + 方块→元素映射
依赖：01-shared-api

---

## 一、职责边界

- 定义 9 种元素类型及其层级
- 提供元素查询（ID → 枚举，按层级列出）
- 定义方块→元素的分解映射数据
- 对接仓库系统的元素存储接口

**不包含：**
- 元素的存储实现（仓库模块负责）
- 元素如何产生（节点建筑负责）
- 元素如何消耗（建造通过原子操作A，合成通过工作站）

---

## 二、元素分层

### 2.1 三层共 9 种

```
第一层（开局可用）          第二层（奇观值解锁）        第三层（高奇观值解锁）
┌─────────────────┐      ┌─────────────────┐      ┌─────────────────┐
│ 土 EARTH         │      │ 火 FIRE          │      │ 金 GOLD          │
│ 木 WOOD          │      │ 铁 IRON          │      │ 钻石 DIAMOND     │
│ 水 WATER         │      │ 风 WIND          │      │ 末影 ENDER       │
└─────────────────┘      └─────────────────┘      └─────────────────┘
```

### 2.2 各元素详细定义

| 元素 | ID | 层级 | 对应节点 | 存储数值类型 |
|------|-----|------|---------|-------------|
| 土 | earth | 1 | 大地节点 | long |
| 木 | wood | 1 | 森林节点 | long |
| 水 | water | 1 | 水域节点 | long |
| 火 | fire | 2 | 地心节点 | long |
| 铁 | iron | 2 | 深层大地节点 | long |
| 风 | wind | 2 | 高山节点 | long |
| 金 | gold | 3 | 金矿节点 | long |
| 钻石 | diamond | 3 | 钻石矿节点 | long |
| 末影 | ender | 3 | 虚空节点 | long |

### 2.3 元素可用性与节点解锁

MVP 阶段第一层的土（EARTH）、木（WOOD）、水（WATER）均可通过对应节点建筑产出。

高层元素的解锁流程：
1. 殖民地奇观值达到阈值 → 自动解锁对应节点建筑的建造权限
2. 玩家建造该节点建筑后，对应元素即可通过采集任务产出
3. 新元素解锁后，依赖该元素的建筑和配方也随之可用

MVP 不包含需要火/铁/风/金/钻石/末影元素的配方，后续版本逐步开放。

---

## 三、方块 → 元素分解映射

### 3.1 仅基础方块可分解

可分解方块通过方块标签 `#wandscape:decomposable` 标记。只有带此标签的方块才可被工作站分解为元素。

```json
// data/wandscape/element_mappings/cobblestone.json
{
  "block": "minecraft:cobblestone",
  "yields": {
    "earth": 4
  }
}
```

```json
// data/wandscape/element_mappings/oak_log.json
{
  "block": "minecraft:oak_log",
  "yields": {
    "wood": 8
  }
}
```

### 3.2 合成产物不可分解

石砖、木板、玻璃、任何合成产物不在 `#wandscape:decomposable` 标签内，无法分解为元素。

### 3.3 方块→元素构建需求

可在 JSON 中定义建造一个方块需要多少元素（用于操作A）：

```json
// data/wandscape/element_mappings/stone_bricks.json
{
  "block": "minecraft:stone_bricks",
  "build_cost": {
    "earth": 4
  },
  "decomposable": false
}
```

---

## 四、核心 API 实现

```java
public class ElementApiImpl implements ElementApi {
    @Override
    public ElementType fromId(String id) {
        return ElementType.valueOf(id.toUpperCase());
    }

    @Override
    public int getTier(ElementType type) {
        return type.getTier();
    }

    @Override
    public List<ElementType> getByTier(int tier) {
        return Arrays.stream(ElementType.values())
            .filter(e -> e.getTier() == tier)
            .toList();
    }

    // 查询方块建造所需元素
    public Map<ElementType, Long> getBuildCost(BlockState block) { /* JSON 查询 */ }

    // 查询方块分解所得元素
    public Map<ElementType, Long> getDecomposeYield(BlockState block) { /* JSON 查询 */ }
}
```

---

## 五、元素间不可互相转化

基础元素（土/木/水）间不可互相转化。唯一的"转化"路径是：
1. 玩家手动采集基础方块 → 工作站分解为元素 → 注入仓库
2. 工作站用元素合成方块物品 → 存入仓库

节点产出哪种元素完全由节点建筑类型决定。

---

## 六、独立测试方案

### 单元测试

1. **枚举完整性**：9 种元素全部可正反查找（ID → 枚举 → ID）
2. **层级分组**：`getByTier(1)` 返回 3 种、`getByTier(2)` 返回 3 种、`getByTier(3)` 返回 3 种
3. **JSON 加载**：`getBuildCost(Blocks.STONE_BRICKS)` 返回 `{EARTH: 4}`
4. **JSON 加载**：`getDecomposeYield(Blocks.COBBLESTONE)` 返回 `{EARTH: 4}`
5. **不可分解**：`isDecomposable(Blocks.STONE_BRICKS)` 返回 false

### 集成测试

1. 放置 `#wandscape:decomposable` 方块，验证系统可正确识别
2. 修改 JSON 配置后 `/reload`，映射立即更新
