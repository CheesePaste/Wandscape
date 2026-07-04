# Architecture 文档扫描报告 & 更新清单（代码清理后）

扫描日期：2026-07-04（更新于清理后）

**代码清理已执行：** Round 1 (17 文件删除) + Round 2 (6 旧类型 + 4 连锁修改 + 6 测试修改)。当前 0 死代码。

---

## 一、README.md (`architecture/README.md`) — 🔴 大修

### 包地图

| 条目 | 状态 | 问题 |
|------|------|------|
| core/ | 🟡 | 部分边界接口；EquipmentComponent 等新类型已存在 |
| engine/ | 🟡 | 缺 transport/、FailureAnalyzerSystem、RoadRoutingHelper |
| shared/ | 🟡 | 缺 log/、叙事事件类型、访问记忆类；`bridge/` 已整体删除 |
| tourist/ | 🟡 | 基本正确，缺 NarrativeGenerator 相关 |
| building/ | 🟡 | 缺 editor/ 子包、client/ 子包、network/ 子包 |
| wand/ | 🔴 | **法杖重构** — 新 JSON 格式(attributes[])，EquipmentComponent 替代 WandCarrier，3 新法杖 |
| element/ | 🟡 | 小改 — SynthesizeMeta.wandLevel 已删除 |
| npc/ | 🟢 | 基本准确 |
| warehouse/ | 🟡 | WarehouseScreen 双标签页重构 |
| production/ | 🟡 | 配方 wand_level 删除，UI 清理 |
| dataconfig/ | 🟢 | 基本准确 |
| command/ | 🟢 | 基本准确 |
| blueprint/editor/ | 🟢 | 基本准确 |
| **projection/** | 🔴 | **完全缺失** |
| **road/** (顶层) | 🔴 | **完全缺失** |
| **task/** (顶层) | 🔴 | **完全缺失** |
| **stats/** | 🔴 | **完全缺失** |
| **standalone/** | 🔴 | **完全缺失** |
| **imgui/** | 🔴 | **完全缺失** |
| **shared/log/** | 🔴 | **完全缺失** |

---

## 二、各包文档逐包评估

### [ ] 1. `core.md` — 🔴 大修

**法杖重构已删除（代码已完成）：**
- `WandCarrier` → `EquipmentComponent`（删除完成）
- `BehaviourTag` / `BehaviourLevel`（删除完成）
- `WandRequirementDeriver`、`WandProvider`、`WandLifecycle`（已删除）
- `AtomicOp.WandEquipOp` / `WandReturnOp`（已移除）
- `GlobalTask.requirements` / `schedulerRetryCount`（已删除）
- `TaskFailureReason.WandRequirementUnmet` / `ColonyEvaluationTooLow`（已删除）
- `TaskRequest.wandRequirementOverrides` / `WorkItem.wandRequirementOverrides`（已删除）

**SchedulerSystem / TaskExecutionSystem / CoreBootstrap / World 清理完成。**

**新增部分（文档缺失）：**
- `core/types/` — 缺：EffectId、InteractAction、EquipmentSlot、AttributeType、AttributeModifier、ModifierOperation、EquipmentPreset（7 个新类型）
- `core/component/` — 缺：EquipmentComponent
- `core/boundary/` — 缺：ResourceAddedListener、ResourceShortageHandler
- `core/system/` — 缺：EventDrivenTaskSource、PlayerManualSource、WorkbenchSource
- `core/task/` — 缺：ApprovalInfo、Blueprint（接口）、BlueprintSteps、CompiledBlueprint、InterruptRecord、ExecutorState、TaskState、TaskCompiler、TriggerDeclaration
- `core/op/` — 缺：ConditionEvaluator、ResourceShortageException
- `core/road/` — 缺：DecorationPoint、MstEdge、PathPoint、RoadBlobCache、RoadBuildingData、RoadNode、RoadRouter、RouteSegment、XZPoint

**已删除不写：**
- `WandCarrier`、`BehaviourTag`、`BehaviourLevel`（整文件删除）
- `IntersectionDetector`（整文件删除）

### [ ] 2. `engine.md` — 🔴 大修

**法杖重构已删除（代码已完成）：**
- `WandEquipExecutor`、`WandReturnExecutor`、`WandProvisionSystem`（已删除）
- `EngineBootstrap` 中所有 wand 相关注册已移除
- `FailureAnalyzerSystem` wand-recovery 逻辑已清理

**新增 (文档缺失)：**
- `engine/transport/ItemTransportManager.java`
- `engine/boundary/WandscapeEntityOps.java`
- `engine/boundary/ResourceRequestExecutor.java`
- `engine/road/RoadRoutingHelper.java`
- `engine/road/RoadConfig.java`
- `engine/road/WandscapeTags.java`
- `engine/road/RoadBlobExplorer.java`

### [ ] 3. `shared.md` — 🔴 大修

**法杖重构已删除/变更（代码已完成）：**
- `TypeBridge.java` — 已删除（`shared/bridge/` 整包已移除）
- `WandApi` — 简化（删 behaviors 方法）
- `WorkItem` — 简化（删 wandRequirementOverrides）
- `BehaviorType`、`AbilitySet`、`WandBehaviorData` — 已删除
- `AtomicStep`、`AtomicExecutor` — 已删除（旧设计）
- `WarehouseEntry` — 已删除（未使用）
- `MapBackedRegistry` — 已删除（被 SimpleDataRegistry 取代）
- `ItemGrid` — 已删除（未使用）

**事件类已删除（从未 fire）：**
- `ColonyDeletedEvent`、`ElementChangedEvent`、`MaintenanceDueEvent`、`MaintenanceTickEvent`
- `NpcDiedEvent`、`NpcRecruitedEvent`、`NpcResurrectedEvent`
- `TaskAwaitingMaterialsEvent`、`TaskCompletedEvent`

**仍存留的事件（需在 shared.md 中补全）：**
- `ColonyCreatedEvent`（保留）
- `DailySettlementEvent`（保留，doc 有但不全）
- `MaintenanceForecastWarningEvent`（保留，doc 有但不全）
- `ShopRestockedEvent`（保留，doc 有但不全）
- `TouristArrivedEvent` / `TouristDepartedEvent`（保留，doc 缺）
- `WonderEffectChangedEvent`（保留，doc 有但不全）

**data/ 缺以下类型：**
- BlueprintInfo
- Emotion
- MageResume
- NarrativeEvent / NarrativeEventType
- ParamTypeInfo
- VisitMemory
- MaintenancePriority

**network/ — 补充：**
- 现有文档只有 2 个包，缺少 ColonyStatsSyncPacket、PanelStateTracker

**log/ — 完全缺失：**
- Log.java、LogFilter.java

**ui/ — 需更新：**
- ui/editor/、ui/task/、ui/util/、ui/animation/ 子包
- 组件列表不完整（Slider、DemoScreen 等）

### [ ] 4. `building.md` — 🟡 需补充

**已删除的文件（不再写入文档）：**
- `BuildingDataImpl`（已删除）
- `BuildingOverlapException`（被扫描标记为"仅测试引用"）

**缺失部分：**
- `building/client/` — HotelScreen, ShopScreen, TavernScreen
- `building/editor/` — BuildingEditor 全套（10文件）
- `building/network/` — 网络包列表
- `building/internal/DemolishCompleteListener.java`
- `building/internal/ShopStockManager`（文档不完整）

### [ ] 5. `tourist.md` — 🟢 基本准确

### [ ] 6. `wand.md` — 🔴 大修（详见 `docs/refactor-wand-behavings.md`）

**代码变更完成度（Stage 1-7 ✅，仅 Stage 8 文档待做）：**
- Stage 5 旧类型清理 ✅（BehaviorType/BehaviourTag/BehaviourLevel/AbilitySet/WandBehaviorData/WandCarrier 全部删除）
- Stage 6 JSON/加载器 ✅
- Stage 7 UI ✅
- Stage 8 测试+文档 ❌（仅文档部分待完成）

### [ ] 7. `element.md` — 🟡 需更新

- `ElementMappingConfig.SynthesizeMeta.wandLevel` 已删除

### [ ] 8. `npc.md` — 🟡 需更新

- `WandscapeNpc.returnEquippedWands()` 已删除
- `EntityComponentBridge` 删除 WandCarrier.class，保留 EquipmentComponent.class
- `WandCarrier` 不再被 NPC 持有

### [ ] 9. `warehouse.md` — 🟡 需更新

- WarehouseScreen 双标签页（Overview + Exchange）
- WarehouseActionPacket 新增 `slotIndex` / `deposit_from_slot`

### [ ] 10. `production.md` — 🟡 大修

- 配方 wandLevel 全部删除
- 网络包 locked_reason 不再支持 "wand_level"
- UI 清理完成

### [ ] 11-14. 其余小包 — 可后续微调

---

## 三、完全缺失的新包文档（需新建）

| # | 包 | 说明 |
|---|-----|------|
| 15 | `projection.md` | 建筑预览/投影系统（7 文件） |
| 16 | `road.md` | 路面编辑器客户端/网络（12 文件） |
| 17 | `stats.md` | 统计系统（5 文件） |
| 18 | `task.md` | 任务编辑器网络层（4 文件） |
| 19 | `standalone.md` | 独立编辑器 |
| 20 | `imgui.md` | ImGui 管理器 |
| 21 | `equipment.md` | 装备系统（设计文档要求新建） |

---

## 四、README.md 修改清单

### 包地图变更

1. 删 `shared/bridge/` 行（已整体移除）
2. 删或更新 core 行（WandCarrier → EquipmentComponent，BehaviourTag/Level 已删）
3. 追加上一节所列 7 个缺失包

### 各包描述更新

- core: "12 个组件"（含 EquipmentComponent，不含 WandCarrier）
- engine: 追加 transport/、FailureAnalyzerSystem
- shared: 追加 log/、叙事事件类型；删 bridge/、已删类型
- wand: 完全重写
- building: 追加 editor/ client/ network/ 子包

### 入口表更新

追加 7 行：projection/road/stats/task/standalone/imgui/equipment

---

## 五、工作量估算（仅文档部分）

| 类别 | 数量 | 预估时间 |
|------|------|---------|
| 现有文档大修 | 5 (shared, core, engine, wand, production) | ~40min 每个 |
| 现有文档补充 | 2 (building, README) | ~25min 每个 |
| 现有文档微调 | 7 (其余) | ~5min 每个 |
| 新建文档 | 7 (projection, road, stats, task, standalone, imgui, equipment) | ~15min 每个 |

**总计约 6-8 小时。**
