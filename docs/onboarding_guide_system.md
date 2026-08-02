# Wandscape 引导系统（Onboarding & Tutorial Guide System）设计文档

> **文档状态**：草案待评审  
> **更新日期**：2026-08-02  
> **适用版本**：NeoForge 1.21.1 / Wandscape 1.3.1a+  

---

## 一、 系统定位与设计原则

Wandscape 拥有两大核心系统（**殖民地自动化**与**游客模拟经营**），包含了复杂的经营界面、建造工具与特效编辑器。为了让新玩家与创作者平滑上手，引导系统必须满足以下原则：

1. **非侵入式高亮（Spotlight Focus）**：通过暗化背景与组件框选高亮（Spotlight Highlight），精确引导玩家注意到关键按钮/区域，避免信息过载。
2. **渐进式解锁（Contextual Progression）**：根据玩家的游戏阶段（如：首次建造市政厅、首次游客入城、首次打开扫描器）触发引导，不进行一次性鸭霸式灌输。
3. **完全数据驱动（JSON Driven）**：引导步骤、文本、高亮目标 ID、触发条件解耦为 JSON 配置，便于后期扩展多语言与调试。
4. **资深向与跳过保护（Replay & Skip）**：提供“跳过引导”与“再次播放”选项，老玩家不被打扰，新手随时可温故知新。

---

## 二、 全屏幕与编辑器引导清单（14 大 UI 屏幕/编辑器全收录）

本模组包含的所有界面、工具 Overlay 及创作者编辑器均已纳入引导系统。分类如下：

```
Wandscape UI 体系
├── 1. 殖民地经营与核心 Screen (8个)
│   ├── TownHallScreen (市政厅)
│   ├── WarehouseScreen (仓库)
│   ├── NodeScreen (资源节点)
│   ├── ShopScreen (商店)
│   ├── HotelScreen (旅馆)
│   ├── TavernScreen (酒馆)
│   ├── CraftingStationScreen / WorkstationScreen (合成/工作站)
│   └── AnomalyScreen (异象/奇观)
├── 2. 角色与实体 Screen (2个)
│   ├── NpcScreen (法师/NPC 角色)
│   └── TouristScreen (游客面板与调试)
├── 3. 玩家建造工具 & Overlay (2个)
│   ├── BuildingSelectionOverlay & WandscapePanelOverlay (选建与 Overview 模式)
│   └── RoadPlacementOverlay (道路铺设与 Replace/Fill 侧边栏)
└── 4. 创作者与开发向编辑器 (2个)
    ├── BuildingScannerScreen (游戏内建筑扫描与数据编辑器)
    └── Magic Circle Web Editor (独立 Web 魔法阵粒子特效编辑器)
```

---

### 1. 殖民地经营与核心 Screen

#### (1) `TownHallScreen`（市政厅管理主界面）
- **功能**：殖民地的神经中枢，展示人口上限、声望/治安、建设计划、解锁新建筑。
- **引导步骤**：
  1. `Spotlight(header)`：介绍当前殖民地名称、等级与总人口上限。
  2. `Spotlight(btn_build_plan)`：指向【建设计划】按钮，引导玩家在此挑选并下发初始建筑蓝图。
  3. `Spotlight(stat_reputation)`：解释游客声望对殖民地升级与游客吸引力的影响。

#### (2) `WarehouseScreen`（仓库与物流调配界面）
- **功能**：展示殖民地物品总库存、箱子流转记录与生产原料预发。
- **引导步骤**：
  1. `Spotlight(inventory_grid)`：展示全局存储物资，讲解 NPC 法师会自动从此处拿取建筑与合成材料。
  2. `Spotlight(supply_gap_tab)`：讲解物资缺口面板（当生产缺材料时自动在此高亮预警）。

#### (3) `NodeScreen`（资源节点采集界面）
- **功能**：发布/取消特定的自动化采集任务，配置维护费与产出元素。
- **引导步骤**：
  1. `Spotlight(btn_toggle_collect)`：高亮【发布/取消采集】按钮，说明按需采集逻辑。
  2. `Spotlight(element_cycle_btn)`：演示利用 CycleButton 切换产出元素类型，配合殖民地元素经济循环。

#### (4) `ShopScreen`（商业商店界面）
- **功能**：展示商品上架列表、售价、游客购买加成及经营收益。
- **引导步骤**：
  1. `Spotlight(goods_list)`：讲解如何挑选商品上架以吸引游客。
  2. `Spotlight(stat_bonus)`：高亮三个有效收益指标（销售额加成、游客停留加成、元素反哺），说明有效加成算法。

#### (5) `HotelScreen`（旅馆住宿界面）
- **功能**：管理客房容量、住宿费、夜间过夜恢复。
- **引导步骤**：
  1. `Spotlight(room_capacity)`：说明旅馆客房数量决定了入城游客能否过夜。
  2. `Spotlight(overnight_stats)`：讲解游客入住对夜间声望与留存的决定性作用。

#### (6) `TavernScreen`（酒馆服务界面）
- **功能**：餐饮服务、情绪恢复与消费次级循环。
- **引导步骤**：
  1. `Spotlight(service_menu)`：介绍酒馆提供的餐饮/娱乐服务项目。
  2. `Spotlight(satisfaction_rate)`：讲解游客满意度如何转化为殖民地经验。

#### (7) `CraftingStationScreen` / `WorkstationScreen`（合成台与工作站）
- **功能**：自动配方挂载、合成引导倒计时（6s）、自动化材料加工。
- **引导步骤**：
  1. `Spotlight(recipe_select)`：选择要生产的高级建材或商品配方。
  2. `Spotlight(craft_progress)`：讲解法师前来使用工作站时的 6 秒合成引导过程。

#### (8) `AnomalyScreen`（异象/奇观界面）
- **功能**：奇观建筑激活、元素能量注入、全城极品 Buff。
- **引导步骤**：
  1. `Spotlight(energy_core)`：展示奇观的核心元素能量槽。
  2. `Spotlight(activate_btn)`：引导玩家注入元素激活奇观，触发全城游客爆发增长。

---

### 2. 角色与实体 Screen

#### (9) `NpcScreen`（法师/NPC 角色面板）
- **功能**：法师等级/属性、当前持有的法杖工具、正在执行的原子任务。
- **引导步骤**：
  1. `Spotlight(current_task)`：显示法师当前的原子任务（如：前往(X,Y,Z)放置方块）。
  2. `Spotlight(wand_slot)`：说明法师手持法杖是执行殖民地自动化的核心媒介。

#### (10) `TouristScreen`（游客面板与调试面板）
- **功能**：右键游客查看，展示停留时间、消费偏好、当前状态与 AI 调试参数。
- **引导步骤**：
  1. `Spotlight(tourist_state)`：解释游客当前状态（VISITING / EXPLORING / SLEEPING）。
  2. `Spotlight(debug_coords)`：提示调试用的目标建筑与坐标，帮助排查游客路径问题。

---

### 3. 玩家建造工具 & Overlay

#### (11) `BuildingSelectionOverlay` & `WandscapePanelOverlay`（Overview 模式与选建）
- **功能**：全局鸟瞰视野，蓝图选建、旋转线框预览、旋转步骤调整、拆除（Demolish）。
- **引导步骤**：
  1. `Spotlight(blueprint_carousel)`：滑动选择要放置的建筑蓝图。
  2. `Spotlight(rotation_controls)`：引导使用快捷键/UI 调整建筑朝向，强调 4 方向自动对齐。
  3. `Spotlight(demolish_btn)`：演示安全的建筑拆除与资源回收流程。

#### (12) `RoadPlacementOverlay`（道路铺设与编辑侧边栏）
- **功能**： Replace 替换 / Fill 立方体填充模式切换，道路材质选择，铺设游客主干道。
- **引导步骤**：
  1. `Spotlight(mode_switch)`：演示 Replace（替换地表）与 Fill（填充地形）模式的区别。
  2. `Spotlight(road_material)`：强调“游客只沿着道路入城”，引导铺设连通城门的道路网。

---

### 4. 创作者与开发向编辑器

#### (13) `BuildingScannerScreen`（游戏内建筑扫描器编辑器）
- **功能**：创作者扫描建筑，编辑维护费、节点配置、商品、元素产出并导出结构 JSON。
- **引导步骤**：
  1. `Spotlight(bounding_box_info)`：确认扫描器锁定的建筑 3D 尺寸与偏移。
  2. `Spotlight(tabs_config)`：依次高亮 Maintenance Cost / Node Config / Shop Goods 编辑页，指导填入数据。
  3. `Spotlight(btn_export)`：高亮【Export JSON】，点击直接在 `.minecraft/wandscape/exports/` 生成数据包文件。

#### (14) `Magic Circle Web Editor`（Web 端魔法阵/粒子特效编辑器）
- **功能**：独立 Web 应用程序，可视化编辑粒子特效、描边密度（beads）、`polygon`/`star` 形状、贝塞尔曲线与动画契约导出。
- **引导步骤**：
  1. `Spotlight(viewport)`：演示 Canvas 实时 3D 粒子视口与快捷拖拽缩放。
  2. `Spotlight(shape_selector)`：演示在 Circle / Polygon / Star 算法间切换。
  3. `Spotlight(curve_editor)`：示范用贝塞尔曲线调节粒子生命周期的 Alpha 与 Scale 渐变。
  4. `Spotlight(btn_export_spec)`：导出符合 `MagicCircleSpec` Schema 的标准 JSON。

---

## 三、 引导系统技术架构与数据格式

### 1. JSON 契约格式 (`architecture/data/onboarding/onboarding_spec.json`)

```json
{
  "$schema": "https://wandscape.wsteam.com/schemas/onboarding_spec.json",
  "guide_id": "guide_townhall_intro",
  "screen_target": "com.wsteam.wandscape.building.client.TownHallScreen",
  "trigger_condition": "COLONY_CREATED_FIRST_OPEN",
  "allow_skip": true,
  "steps": [
    {
      "step_id": "step_header",
      "target_widget_id": "townhall_header_title",
      "title_key": "gui.wandscape.guide.townhall.header.title",
      "content_key": "gui.wandscape.guide.townhall.header.desc",
      "dialog_position": "BOTTOM_CENTER",
      "highlight_padding": 4
    },
    {
      "step_id": "step_build_button",
      "target_widget_id": "btn_open_build_plans",
      "title_key": "gui.wandscape.guide.townhall.build.title",
      "content_key": "gui.wandscape.guide.townhall.build.desc",
      "dialog_position": "LEFT_ALIGN",
      "highlight_padding": 2
    }
  ]
}
```

### 2. 代码架构拆分

- **接口层 (`shared/api/guide/`)**
  - `OnboardingManager.java`：注册引导契约、记录玩家已完成的 `guide_id` 集合、控制重置。
- **UI 渲染层 (`shared/ui/guide/`)**
  - `SpotlightOverlay.java`：渲染高亮遮罩，通过 Stencil/Scissor 裁剪出目标 Widget 区域，并绘制中世纪风格气泡框。
  - `WidgetLocator.java`：通过 ID 递归查找当前 Screen 内注册的 `AbstractWidget` 坐标与尺寸。

---

## 四、 评审与下一步计划

1. **评审重点**：
   - 14 大 Screen / Overlay 清单是否完整覆盖当前与规划中的所有交互界面？
   - 高亮遮罩 + 气泡对话框的样式是否符合 Wandscape 的中世纪暗色优雅调性？
2. **后续实施步骤**：
   - 确认无误后，先创建 `architecture/data/onboarding_spec.md` 契约文档。
   - 客户端实现 `SpotlightOverlay` 渲染器与组件查找器。
