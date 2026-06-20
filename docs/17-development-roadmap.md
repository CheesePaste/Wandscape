# 开发路线图

文档编号：NEW-17
版本：2.0
状态：四线并行的分阶段开发计划，每阶段可进游戏验证

---

## 一、四条工作线

| 线 | 负责人技能 | 产出物 | 依赖 |
|----|----------|--------|------|
| **程序** | Java / NeoForge | 模块代码、注册、事件、BE、GUI 逻辑 | 美术线提供资源 ID |
| **美术** | 像素画 / 建模 / 粒子 | 方块纹理、物品纹理、NPC 模型+贴图、GUI 贴图、粒子贴图 | 程序线提供注册 ID 和规格 |
| **建筑结构** | 建筑/关卡设计 | 每种建筑的 pattern + block_mapping JSON、多方块 layers 布局 | 美术线提供方块纹理 |
| **数据配置** | 数值/系统策划 | 法杖预设、元素映射、配方、仪式 JSON、TOML 默认值 | 程序线提供 JSON schema |

### 跨线协作规则

- **程序线是主线**：每阶段程序线先定义注册 ID 和 JSON schema → 美术/建筑/数据线才能开工
- **美术线可提前**：如果注册 ID 已确定，美术可以在程序实现前先出贴图
- **建筑线依赖美术线**：建筑结构里引用的方块 ID 需要对应纹理
- **数据线依赖程序线**：JSON 格式由程序线定义 schema，数据线填充内容

---

## 二、路线原则

1. **每阶段结束产出可玩成果**：不搞"全部模块写完再一起调试"
2. **按依赖关系排序**：被依赖的先做，依赖别人的后做
3. **01-shared-api 渐进式补全**：不是一次性写完所有接口，而是每进入一个模块补该模块需要的接口子集
4. **16 data-driven-config 伴随全程**：不单独设阶段，每阶段涉及的 JSON 格式同步定义
5. **99-open-questions + 98-resolved-issues 伴随全程**：遇到问题即时记录到 99，解决了即时移到 98
6. **architecture/ 文件伴随全程**：每完成一个模块的包/注册/事件，立即更新对应 architecture 文件

---

## 三、阶段 0：地基

**目标**：类型系统就绪，四条线的前置准备完成。

**预计**：2-3 天

### 程序线

| 模块 | 交付 |
|------|------|
| `01-shared-api` 骨架 | `BehaviorType`、`ElementType`、`TaskStatus` 枚举 |
| | `AbilitySet`、`AtomicStep`（sealed + OperationA/B/C/D）、`TaskTemplate` record |
| | `ElementStore`、`WandBehaviorData` 接口 |
| | `WandscapeApis` 骨架（setter/getter，未注册抛异常） |
| | `WandscapeConstants` 默认值常量 |
| `16 data-driven-config` 骨架 | `WandscapeDataRegistry<T>` 泛型接口 |
| | `SimpleJsonResourceReloadListener` 框架代码 |

### 美术线

| 交付 | 说明 |
|------|------|
| 模组图标 | `wandscape.png` 模组 logo |
| 颜色板 | 元素 9 色 + 法杖粒子色（金/绿/蓝/红/紫）+ UI 主色调 |
| 方块模板 | 16×16 方块纹理模板（正面/侧面/顶面） |

### 建筑结构线

此阶段无交付。等待阶段 1 程序线定义 BuildingConfig schema。

### 数据配置线

| 交付 | 说明 |
|------|------|
| TOML 骨架 | `config/wandscape-common.toml` 所有配置项 + 默认值 |

### 不做的

- 不定义用不到的接口（`WarehouseApi`、`TaskApi` 等到对应阶段再补）
- 不画具体方块纹理（阶段 1 补）
- 不定义具体建筑结构（阶段 1 补）

---

## 四、阶段 1：可视化

**目标**：玩家手持法杖，世界中有建筑方块。第一个可截图里程碑。

**预计**：程序 3-5 天 / 美术 3-5 天 / 建筑 1-2 天 / 数据 1-2 天

### 程序线

| 顺序 | 模块 | 交付 |
|------|------|------|
| 1st | `02 wand-system` | 法杖物品注册（`DeferredRegister.Items`） |
| | | NBT 结构：`wand_color`、`behaviors`、`range`、`mana_cost_multiplier` |
| | | 创造模式标签页 |
| | | `WandApi` 接口 + 实现（`computeAbilities()`、`getBehaviorData()`） |
| | | 法杖预设 JSON 加载（`data/wandscape/wands/`） |
| 2nd | `03 element-system` | 元素按 ID 检索实现 |
| 并行 | | 方块→元素映射 JSON 加载（`data/wandscape/element_mappings/`） |
| | | `#wandscape:decomposable` 方块标签读取 |
| | | `ElementApi` 接口 + 实现 |
| 3rd | `08 building-core` | 建筑 JSON 注册加载（`data/wandscape/buildings/`）+ `BuildingConfig` 解析 |
| | | `AbstractWandscapeBE`：FIFO 队列 + `colonyId` 缓存 + `onLoad()` |
| | | 注册 2-3 种建筑方块（市政厅、森林节点、大地节点） |
| | | `BuildingApi` 接口 + 实现（`getBuilding()`、`registerBuildingType()`） |
| | | 方块放置触发结构验证 |

### 美术线

| 交付 | 规格 | 说明 |
|------|------|------|
| 法杖物品纹理 | 16×16 | 4-5 种法杖（builder/gatherer/crafter/ritual/basic） |
| 法杖手持模型 | JSON model | 右手持法杖的 item model |
| 建筑方块纹理 | 16×16 每面 | 市政厅、森林节点、大地节点各 1 套 |
| 方块模型 | JSON block model | 对应每种建筑方块的 blockstate + model |
| 创造标签图标 | 16×16 | 法杖图标用作标签页 icon |

### 建筑结构线

| 交付 | 说明 |
|------|------|
| `town_hall.json` | 市政厅 3×3 基座 + 中心柱 pattern（~6-10 方块） |
| `forest_node.json` | 森林节点 3 原木 + 3 模组方块 pattern |
| `earth_node.json` | 大地节点 3 石砖 + 3 模组方块 pattern |
| `block_mapping` | 每个建筑的坐标→方块 ID 映射（含原版方块和模组方块） |

### 数据配置线

| 交付 | 内容 |
|------|------|
| 法杖预设 JSON | `builder_wand.json`、`gatherer_wand.json`、`crafter_wand.json`、`ritual_wand.json` |
| 元素映射 JSON | 首批 5-8 种原版方块的 `build_cost` + `decompose_yield` |
| 建筑 JSON | `town_hall.json`、`forest_node.json`、`earth_node.json` 的数值部分（comfort/magic/wonder/maintenance_cost/queue） |

### 跨线依赖

```
程序定义注册 ID ──→ 美术根据 ID 制作纹理 ──→ 建筑引用 ID 编写 pattern
程序定义 JSON schema ──→ 数据线填充 JSON 数值
```

### 阶段 1 验证

```
进游戏 → 创造标签看到法杖 → 手持法杖（自定义纹理 + 模型）
       → 放置建筑方块 → 建筑在游戏中有正确的纹理和模型
       → /reload → JSON 热重载生效
```

---

## 五、阶段 2：闭环

**目标**：NPC 接到任务 → 走到目标 → 执行操作改造方块。第一个可玩闭环。

**预计**：程序 5-7 天 / 美术 3-4 天 / 建筑 0 天 / 数据 2-3 天

### 程序线

| 顺序 | 模块 | 交付 |
|------|------|------|
| 4th | `07 npc-system` | NPC 实体注册（`wandscape_npc`，法师村民模型） |
| | | 基本属性：生命/魔力/法术强度/恢复速率 |
| | | 魔力每 tick 自然恢复 |
| | | 空闲/工作中/死亡 三状态机 + 直接传送（不走寻路） |
| | | `NpcApi` 接口 + 实现 |
| | | NPC 背包 = 能力来源，`computeAbilities()` 自动合并 `ritual:1` |
| | | NBT 持久化（`addAdditionalSaveData` / `readAdditionalSaveData`） |
| 5th | `05 atomic-operations` | `OperationA` 完整执行：源校验→元素扣除→粒子→改方块→回收 |
| | | `OperationD` 瞬发仪式（`self_teleport`、`item_transport`） |
| | | `OperationB` stub（只做 `node_gathering`） |
| | | `AtomicExecutor` 统一入口，返回 `CompletableFuture<ExecutionResult>` |
| | | 魔力消耗公式 + 源方块不匹配兜底 |
| 6th | `06 task-system` | 全局任务池（`ConcurrentHashMap` + 按 priority 排序） |
| | | 2s 调度器心跳：收集空闲 NPC → 能力匹配 → 评分分配 |
| | | NPC 评分：`spellPower×10 + currentMana×0.1 + 连续执行加成` |
| | | 完整状态机（待审批→待分配→进行中→已完成，含中断/物资等待分支） |
| | | 私有池 + 建筑队列 → `tryPublishNext()` → 全局池 |
| | | 中断冷却（5 分钟）+ 卡死自动重置（15 秒/3 次） |
| | | `TaskApi` 接口 + 实现 |

### 美术线

| 交付 | 规格 | 说明 |
|------|------|------|
| NPC 模型 | 村民改造 | 法师村民模型（可复用原版村民模型+改纹理） |
| NPC 纹理 | 64×64 | 法师袍纹理，1 套（后续版本可加变体） |
| 法杖光束粒子 | 粒子贴图 | 彩色直线粒子束（穿墙），颜色按法杖 `wand_color` |
| 方块转化粒子 | 粒子贴图 | 放置/破坏方块时的粒子爆发效果 |
| 传送粒子 | 粒子贴图 | NPC 传送时的粒子圈效果 |
| NPC 空闲动画 | — | 收起法杖的 idle 姿态 |
| NPC 工作动画 | — | 举起法杖施法的姿态 |

### 建筑结构线

此阶段无新增交付。使用阶段 1 的建筑结构进行测试。

### 数据配置线

| 交付 | 内容 |
|------|------|
| 仪式 JSON | `self_teleport.json`（瞬发，mana_cost=10）、`item_transport.json`（瞬发，mana_cost=1） |
| 建筑队列配置 | 各建筑 JSON 中补充 `queue.task_types` |
| NPC 属性 TOML | `config/wandscape-common.toml` 中 NPC 段落落地 |

### 跨线依赖

```
程序定义 NPC 注册 ID ──→ 美术制作 NPC 模型+纹理
程序定义粒子 ID    ──→ 美术制作粒子贴图
程序定义 ritual JSON schema ──→ 数据线填充仪式 JSON
```

### 阶段 2 验证

```
生成 NPC → NPC 有正确的模型+纹理+动画
放置建筑方块 → BE 入队建造任务 → NPC 接到任务
→ NPC 直接传送到目标位置 → 举起法杖施法
→ 举起法杖 → 彩色光束粒子击中目标 → 方块被放置/破坏
→ 任务完成 → NPC 收起法杖回到空闲状态
```

---

## 六、阶段 3：经济循环

**目标**：采集→存储→消耗 完整经济链。殖民地资源正向循环。

**预计**：程序 5-7 天 / 美术 4-5 天 / 建筑 2-3 天 / 数据 3-4 天

### 程序线

| 顺序 | 模块 | 交付 |
|------|------|------|
| 7th | `04 warehouse-system` | 仓库方块+BE（继承 `AbstractWandscapeBE`） |
| | | 元素存储 + 物品存储实现 |
| | | 差量保存（脏标记 + 5 分钟定时 + 区块卸载 + 批量写入） |
| | | `WarehouseApi` 接口 + 实现 |
| | | 仓库 GUI（`Screen` + `AbstractContainerMenu`） |
| 8th | `09 node-building` | 森林节点 BE（继承 `AbstractWandscapeBE`，自动入队采集任务） |
| | | 大地节点 BE |
| | | 补全 `OperationB.node_gathering`：NPC 到节点→引导→元素入仓 |
| 9th | `10 production-stations` | 工作站 BE+GUI（decompose + synthesize） |
| | | 制作站 BE+GUI（craft_wand） |
| | | 配方 JSON 加载 + `WandscapeDataRegistry<RecipeConfig>` |
| | | 补全 `OperationB.decompose` / `synthesize` / `craft_wand` |

### 美术线

| 交付 | 规格 | 说明 |
|------|------|------|
| 仓库方块纹理 | 16×16 | 仓库正面/侧面/顶面 |
| 仓库 GUI 贴图 | 256×256 | 元素储量槽 + 物品列表区域 + 进度条 |
| 森林节点纹理 | 16×16 | 木质魔法节点外观 |
| 大地节点纹理 | 16×16 | 石质魔法节点外观 |
| 工作站纹理 | 16×16 | 万能工作台外观 |
| 制作站纹理 | 16×16 | 法杖制作台外观 |
| 工作站 GUI | 256×256 | 配方选择 + 输入/输出槽 + 进度箭头 |
| 制作站 GUI | 256×256 | 法杖配方选择 + NBT 预览 |
| 引导粒子 | 粒子贴图 | 节点采集/工作站合成的持续引导粒子环 |
| 元素图标 | 16×16 每个 | 9 种元素的 GUI 小图标 |

### 建筑结构线

| 交付 | 说明 |
|------|------|
| `warehouse.json` | 仓库建筑结构 pattern + block_mapping（~10-15 方块） |
| 更新 `forest_node.json` | 确认阶段 1 的 pattern 可用于采集功能 |
| 更新 `earth_node.json` | 同上 |
| `workstation.json` | 工作站建筑结构 pattern + block_mapping（~8-12 方块） |
| `crafting_station.json` | 制作站建筑结构 pattern + block_mapping（~8-12 方块） |

### 数据配置线

| 交付 | 内容 |
|------|------|
| 工作站分解配方 | 首批 8-10 种原版方块的 decompose 配方 |
| 工作站合成配方 | 首批 5-8 种原版方块的 synthesize 配方 |
| 法杖制作配方 | `craft_builder_wand.json`、`craft_gatherer_wand.json` 等 |
| 节点配置 | `forest_node.json` 中 `node_config` 的 `amount_per_harvest`、`channel_ticks` |
| TOML 补充 | 仓库保存间隔、工作站引导时间等配置项 |

### 跨线依赖

```
程序定义仓库/节点/工作站注册 ID ──→ 美术制作对应纹理
程序定义 GUI 布局规格        ──→ 美术绘制 GUI 贴图
美术完成建筑方块纹理          ──→ 建筑线编写 pattern + block_mapping
程序定义配方 JSON schema    ──→ 数据线填充配方内容
```

### 阶段 3 验证

```
放置森林节点 → 节点有正确的纹理外观
→ 自动发布采集任务 → NPC 执行 node_gathering → 引导粒子环
→ 木元素注入仓库 → 仓库 GUI 显示元素储量增加 + 元素图标
→ 放置工作站（正确纹理）→ 打开 GUI → 下达分解 64 圆石
→ NPC 执行 decompose → 土元素入仓 → 圆石消失
→ 放置制作站 → 下达制作建筑法杖 → 消耗元素 → 法杖产出（带 NBT）
```

---

## 七、阶段 4：殖民地玩法

**目标**：从零建立殖民地，招募 NPC，分配房屋，魔力池运转。

**预计**：程序 5-7 天 / 美术 3-4 天 / 建筑 2-3 天 / 数据 2-3 天

### 程序线

| 顺序 | 模块 | 交付 |
|------|------|------|
| 10th | `11 housing-mana-pool` | 房屋 BE：绑定/解绑 NPC，空闲 NPC 返回房屋 |
| | | 魔力池 BE：公共魔力存储 + 充能/抽取 |
| | | `HouseApi` + `ManaPoolApi` 接口 + 实现 |
| | | 补全 `OperationB.charge` / `extract`（魔力池交互） |
| 11th | `15 colony-lifecycle` | 殖民地创建流程（放市政厅→选区域→确认→生成 colonyId） |
| | | 殖民地删除（BE 惰性模式，方案 B） |
| | | 三数值初始化 + 维护结算（20 分钟扣除木元素） |
| | | `ColonyApi` 接口 + 实现 |
| | | `Map<ChunkPos, Set<UUID>>` 殖民地查询缓存 |
| 12th | `12 tavern-recruitment` | 酒馆 BE+GUI：候选人三选一展示 + 刷新/招募 |
| | | `RecruitmentCandidate` 属性随机生成 |
| | | `TavernApi` 接口 + 实现 |
| | | 舒适值限制候选人属性上限 |
| | | 招募流程：三选一 → 生成 NPC → `NpcRecruitedEvent` → 分配房屋 |

### 美术线

| 交付 | 规格 | 说明 |
|------|------|------|
| 房屋纹理 | 16×16 | 法师小屋外观 |
| 魔力池纹理 | 16×16 | 发光魔力池外观（含动画帧） |
| 酒馆纹理 | 16×16 | 酒馆建筑外观 |
| 酒馆 GUI | 256×256 | 三候选人卡片 + 属性展示 + 刷新/招募按钮 |
| 市政厅纹理 | 16×16 | 更新阶段 1 的纹理，更精致 |
| 魔力池充能粒子 | 粒子贴图 | NPC 向魔力池充能/抽取时的粒子流 |
| NPC 房屋绑定粒子 | 粒子贴图 | NPC 绑定/返回房屋时的特效 |

### 建筑结构线

| 交付 | 说明 |
|------|------|
| `mage_house.json` | 房屋 3×3×3 小建筑 pattern + block_mapping |
| `mana_pool.json` | 魔力池 3×3 基座 pattern + block_mapping |
| `tavern.json` | 酒馆 5×5×4 建筑 pattern + block_mapping |
| 更新 `town_hall.json` | 市政厅升级为正式结构（阶段 1 是简易版） |

### 数据配置线

| 交付 | 内容 |
|------|------|
| 房屋 JSON | `mage_house.json` 的 `comfort`/`maintenance_cost` 等数值 |
| 魔力池 JSON | `mana_pool.json` 的 `mana_pool_config`（容量、充能倍率） |
| 酒馆 JSON | `tavern.json` 的 `tavern_config`（候选人刷新消耗、属性范围） |
| TOML 补充 | 殖民地默认半径、维护间隔、招募冷却等配置项 |

### 跨线依赖

```
程序定义房屋/魔力池/酒馆/市政厅注册 ID ──→ 美术制作纹理
美术完成纹理                          ──→ 建筑线编写 pattern
程序定义 recruitment schema          ──→ 数据线填充候选人属性范围
```

### 阶段 4 验证

```
放置市政厅 → 市政厅有正式纹理 → 创建殖民地（选区域）
→ 放置房屋 + 魔力池 + 酒馆（均有正确纹理+结构）
→ 酒馆 GUI 显示 3 个候选人（卡片+属性+头像）
→ 招募 1 个 NPC → NPC 入住房屋 → 房屋绑定粒子
→ NPC 空闲时返回房屋 → 魔力恢复 ×3 → 魔力池充能粒子
→ 维护周期到达 → 木元素正确扣除（不足 → 建筑自动关停）
```

---

## 八、阶段 5：高级功能

**目标**：仪式祭坛 + 远程管理面板。殖民地管理的完整体验。

**预计**：程序 5-7 天 / 美术 3-4 天 / 建筑 2-3 天 / 数据 1-2 天

### 程序线

| 顺序 | 模块 | 交付 |
|------|------|------|
| 13th | `13 ritual-altar` | 祭坛 BE（继承 `AbstractWandscapeBE`）+ 多方块检测 |
| | | `MultiblockValidator` + 缓存优化（放置/破坏事件触发，不每 tick 检测） |
| | | 复活仪式 `OperationD(ritualId="resurrection")` 完整执行 |
| | | 其他仪式 stub：`rain_call`、`guardian_barrier`、`mass_vigor`、`warp_gate` |
| 14th | `14 management-panel` | 管理面板 GUI：三数值、元素储量、NPC 列表、建筑列表 |
| | | 小地图渲染（殖民地范围内显示建筑/NPC 图标，MVP 不渲染地形） |
| | | 远程建造：选建筑→小地图放置→矩形线框预览→确认→生成建造任务 |
| | | 远程管理：关停/重启建筑、查看 NPC 背包（只读）、重新分配房屋 |

### 美术线

| 交付 | 规格 | 说明 |
|------|------|------|
| 祭坛核心纹理 | 16×16 | 祭坛核心方块 |
| 符文柱纹理 | 16×16 | 祭坛多方块的符文柱 |
| 管理面板 GUI | 全屏 | 殖民地总览面板（多个 tab 页） |
| 小地图图标 | 16×16 每个 | 每种建筑的 minimap 图标 + NPC 图标 |
| 远程建造预览线框 | 渲染 | 矩形边界线框着色器或粒子线框 |
| 复活仪式粒子 | 粒子贴图 | 长引导复活仪式的粒子特效 |
| 祭坛激活动画 | 动画 | 祭坛多方块成型时的激活特效 |

### 建筑结构线

| 交付 | 说明 |
|------|------|
| `ritual_altar.json` | 祭坛多方块结构（`data/wandscape/multiblocks/`） |
| | 2 层 layers：底层 3×3 石砖+祭坛核心，上层符文柱围圈 |
| 更新所有建筑结构 | 根据阶段 1-4 的测试反馈调整 pattern |

### 数据配置线

| 交付 | 内容 |
|------|------|
| 祭坛多方块 JSON | `ritual_altar.json` 的 layers + mapping + controller_pos |
| 复活仪式 JSON | `resurrection.json`：channel_ticks=2400, mana_cost=100, element_cost |
| 后续仪式 stub | `rain_call.json`、`guardian_barrier.json`、`warp_gate.json` |
| TOML 补充 | 祭坛相关配置项 |

### 跨线依赖

```
程序定义祭坛多方块检测逻辑 ──→ 建筑线设计祭坛 layers 布局
美术完成符文柱/祭坛核心纹理  ──→ 建筑线编写祭坛 block_mapping
程序定义管理面板 GUI 布局    ──→ 美术绘制管理面板贴图
程序定义建筑 category 枚举   ──→ 美术绘制各 category 的小地图图标
```

### 阶段 5 验证

```
按多方块结构放置祭坛 → 祭坛激活动画 → 多方块检测通过
→ 打开管理面板 → 看到三数值/元素储量/建筑列表/NPC 列表（正确排版+贴图）
→ 小地图显示建筑图标 + NPC 图标（按 category 着色）
→ 远程下达建造任务 → 矩形线框预览 → 确认 → 任务入全局池
→ NPC 死亡 → 祭坛自动发布复活任务 → 引导粒子 120s → NPC 复活
```

---

## 九、阶段汇总

| 阶段 | 名称 | 程序 | 美术 | 建筑结构 | 数据配置 | 累计成果 |
|------|------|------|------|---------|---------|---------|
| 0 | 地基 | 01 骨架 + 16 | 图标+色板 | — | TOML 骨架 | 编译通过，类型系统就绪 |
| 1 | 可视化 | 02 + 03 + 08 | 法杖+建筑纹理 | 3 种建筑结构 | 法杖+元素+建筑 JSON | 手持法杖，建筑而立 |
| 2 | 闭环 | 07 + 05 + 06 | NPC+粒子+动画 | — | 仪式 JSON + NPC TOML | NPC 执行建造任务 |
| 3 | 经济 | 04 + 09 + 10 | 仓库/节点/站纹理+GUI+元素图标 | 5 种建筑结构 | 配方 JSON + 节点配置 | 采集→存储→合成闭环 |
| 4 | 殖民地 | 11 + 15 + 12 | 房屋/池/酒馆纹理+GUI | 4 种建筑结构 | 房屋/酒馆 JSON + 殖民地 TOML | 从零建立殖民地 |
| 5 | 高级 | 13 + 14 | 祭坛+面板纹理+粒子+图标 | 祭坛多方块 | 祭坛+仪式 JSON | 仪式复活 + 远程管理 |

---

## 十、与原计划的关键差异

| 原计划 (P0-P4) | 本路线图 | 变更理由 |
|----------------|---------|---------|
| P0: 原子操作(05)先于建筑(08) | 建筑(08)先于原子操作(05) | OperationB 需要建筑 BE 存在才能测试 |
| P2: 仓库(04)在阶段 3 | 仓库(04)仍在阶段 3 但 08 已就绪 | 仓库是建筑，需继承 `AbstractWandscapeBE` |
| P0-P1: 任务(06)与 NPC(07)同级 | 任务(06)在 NPC(07)+原子(05)之后 | 调度器需要 NPC 实体 + 执行器才能验证 |
| P4: 殖民地(15)在最后 | 殖民地(15)放阶段 4 | 需要仓库/房屋/酒馆就绪后才能闭环 |
| 未分线 | 四线并行 | 程序/美术/建筑/数据可并行推进 |

---

## 十一、各线并行建议

```
阶段 0:  [程序════]  [美术══]                      [数据══]
阶段 1:  [程序══════]  [美术══════]  [建筑══]  [数据══════]
阶段 2:  [程序══════════]  [美术══════]            [数据════]
阶段 3:  [程序══════════]  [美术════════]  [建筑════]  [数据════════]
阶段 4:  [程序══════════]  [美术══════]  [建筑════]  [数据════]
阶段 5:  [程序══════════]  [美术══════]  [建筑══]  [数据══]
```

- 程序线每阶段先开工（定义 ID 和 schema），美术和建筑线随后跟进
- 美术线可在程序实现前先出纹理（只要有注册 ID）
- 建筑线需等美术线出纹理后才能确定 block_mapping
- 数据线需等程序线定义 JSON schema 后才能填充
