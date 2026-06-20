# Blueprint JSON DSL Specification

**Version:** 0.1 draft
**Date:** 2026-06-20
**Status:** Design in progress — incomplete

---

## 0. 定位与核心决策

### 0.1 Building JSON vs Blueprint JSON

| | Building JSON | Blueprint JSON |
|---|---|---|
| **角色** | 数据容器 | 逻辑容器 |
| **位置** | `data/wandscape/buildings/` | `data/wandscape/blueprints/` |
| **内容** | 静态属性（comfort/magic/pattern/boundary/…）+ 蓝图引用 | DSL 步骤序列 + 参数声明 |
| **编译** | 自身不编译，引用的 blueprint 编译为 lambda | 编译为 `BlueprintSteps`（函数：params → TaskSequence） |
| **运行时** | 字段打包进 TaskRequest.params | 解释器执行 → 生成 TaskSequence |

Building JSON 里的 `"blueprint": {"id": "...", "bind": {...}}` 本质是一次顶层 `call`。

### 0.2 核心原则（已决策）

| # | 决策 | 说明 |
|---|------|------|
| 1 | **运行时解释** | DSL 在每次任务提交时解释执行，不编译期预绑定参数。无 IR 缓存（V1） |
| 2 | **强类型参数** | 蓝图声明参数类型列表，调用时传入的值需匹配。底层类型合并为 6 种 |
| 3 | **扁平步骤数组** | 根层是 `steps: [...]`，`for_each` / `if` / `call` 内嵌子步骤数组 |
| 4 | **表达式 AST** | 非平凡值用 JSON 节点表达，简单引用用裸类型推断 |
| 5 | **编译器在 core/** | 纯 Java 21。core 允许 Gson 依赖（`JsonElement` 作为值载体） |
| 6 | **call = 宏展开** | 子蓝图步骤内联到调用者序列。独立任务走 `emit_event` |
| 7 | **全数据打包** | 所有 bind 所需字段在入队时塞进 `TaskRequest.params`，解释器不自查 building |
| 8 | **enqueuer 做 rename** | 入队时代码负责 bind（building字段名 → 蓝图参数名）+ 构造 anchor。解释器单一职责：面对干净的蓝图参数 |
| 9 | **anchor 硬编码** | 建筑 enqueuer 硬编码 `anchor = [x, y, z]`。这是建筑领域知识，不放蓝图 DSL |
| 10 | **`Map<String, JsonElement>`** | `WorkItem.params` / `TaskRequest.params` / `BlueprintSteps.generate()` 全部改为 `Map<String, JsonElement>`。旧 lambda 内部 `.getAsString()` |
| 11 | **不分 `type` 字段** | building 和 blueprint 靠文件夹区分（`buildings/` vs `blueprints/`），JSON 内不加 `type` |
| 12 | **`bind` = 简单重命名** | `bind` 的 key = 蓝图参数名，value = `$field_name` 裸变量引用。不做运算，不搞 `${}` 特殊语法 |
| 13 | **参数池 = 蓝图参数名** | 解释器看到的变量名全是蓝图参数名。building 字段名不在解释器视野内 |

### 0.3 数据流（运行时）

```
玩家右键 / BlockPlaceHandler
  │
  │ 1. 读 BuildingConfig.blueprint.bind
  │ 2. 逐条 resolve：$字段名 → 从 building JSON 取值
  │ 3. 硬编码：anchor = [x, y, z]
  │ 4. 所有值以蓝图参数名为 key 写入 WorkItem.params (Map<String, JsonElement>)
  ▼
BuildingTaskSource.poll()
  → TaskRequest(blueprintId="build:place_structure", params={offsets: [...], blocks: {...}, name: "...", anchor: [0,64,0]})
  → BlueprintRegistry.compile(request)
      → BlueprintInterpreter.interpret(dsl, params)
          → 解析表达式 → 展开 for_each / if / call
          → TaskSequence(AtomicOp[], label)
  → GlobalTask(sequence)
```

### 0.4 参数命名空间

```
Building JSON 字段名（世界 A）          Blueprint 参数名（世界 B）
  pattern       ──bind──►              offsets
  block_mapping ──bind──►              blocks
  display_name  ──bind──►              name
  (x/y/z)       ──enqueuer 硬编码──►   anchor
```

enqueuer 吃掉世界 A，吐出世界 B 的参数池。解释器只看到世界 B。

---

## 1. 蓝图 JSON Schema

```json
{
  "id":              "<string> (required)",
  "type":            "\"blueprint\" (required — 区分建筑 JSON)",
  "display_name":    "<string> (optional)",
  "description":     "<string> (optional)",
  "params": {
    "<name>": "<type>"   // 见 §1.1
  },
  "steps": [
    // 见 §2 步骤类型
  ]
}
```

### 1.1 参数类型系统

**底层类型** (只这 6 种，其他语义类型是 alias)：

| 类型 | JSON 形态 | 示例 |
|------|----------|------|
| `string` | `"..."` | `"minecraft:stone_bricks"` |
| `int` | `42` | `5` |
| `pos` | `[x, y, z]` | `[0, 64, -1]` |
| `list<pos>` | `[[x,y,z], ...]` | `[[-1,0,0], [1,0,0]]` |
| `list<string>` | `["a", "b"]` | `["crafting", "ritual"]` |
| `map<string,string>` | `{"k":"v"}` | `{"0,0,0": "minecraft:stone"}` |

**隐式转换**（仅在表达式求值时自动应用）：

| from | to | 规则 |
|------|----|------|
| `int` | `string` | `42` → `"42"` |
| `pos` | `string` | `[1,2,3]` → `"1,2,3"`（= `BlockOffset.toKey()`） |

**`list<pos>` 可直接被 `for_each` 迭代，`map<string,string>` 可用 `get` 查询。**

---

## 2. 步骤类型

### 2.1 `place` — 放置方块

```json
{
  "type": "place",
  "at": "<表达式 → pos>",
  "block": "<表达式 → string>"
}
```

→ 生成 `TransformOp.place(at, block)`

### 2.2 `remove` — 拆除方块

```json
{
  "type": "remove",
  "at": "<表达式 → pos>",
  "from": "<表达式 → string>"
}
```

→ 生成 `TransformOp.remove(at, from, drops=[])`

### 2.3 `convert` — 转换方块

```json
{
  "type": "convert",
  "at": "<表达式 → pos>",
  "from": "<表达式 → string>",
  "to": "<表达式 → string>"
}
```

→ 生成 `TransformOp.convert(at, from, to)`

### 2.4 `block_interact` — 方块交互

```json
{
  "type": "block_interact",
  "at": "<表达式 → pos>",
  "action": "<\"toggle\"|\"activate\"|\"open_gui\">"
}
```

→ 生成 `BlockInteractOp(at, InteractAction)`

### 2.5 `entity_interact` — 实体交互

```json
{
  "type": "entity_interact",
  "target": "<表达式 → entity_id string>",
  "effect": "<表达式 → string>",
  "strength": "<表达式 → int>",
  "duration": "<表达式 → int>"
}
```

→ 生成 `EntityInteractOp(target, effect, strength, duration)`

### 2.6 `ritual` — 仪式

```json
{
  "type": "ritual",
  "ritual": "<表达式 → string>",
  "at": "<表达式 → pos>",
  "channel_ticks": "<表达式 → int>"
}
```

→ 生成 `RitualOp(ritual, at, channelTicks)`

### 2.7 `request_resource` — 请求资源

```json
{
  "type": "request_resource",
  "resource": "<表达式 → string>",
  "amount": "<表达式 → int>"
}
```

→ 生成 `ResourceRequestOp(resource, amount)`

### 2.8 `emit_event` — 发出事件

```json
{
  "type": "emit_event",
  "event": "<表达式 → string>",
  "data": {
    "<key>": "<表达式 → string>"
  }
}
```

→ 生成 `EmitEventOp(eventName, data)`

### 2.9 `for_each` — 循环展开

```json
{
  "type": "for_each",
  "list": "<表达式 → list<pos> 或 list<string>>",
  "var": "<string — 循环变量名>",
  "steps": [
    // 子步骤，可引用 $<var> 和外部变量
  ]
}
```

编译时：`list` 求值 → 对每个元素展开 `steps` 数组一次。`var` 在每次迭代中绑定当前元素。

循环变量类型由 list 的元素类型决定：`list<pos>` → var 是 `pos`，`list<string>` → var 是 `string`。

### 2.10 `if` — 条件分支

```json
{
  "type": "if",
  "condition": "<表达式 → string — 条件名>",
  "params": { "<k>": "<表达式 → string>" },
  "else_invert": "<bool (default false)>",
  "then": [
    // condition=true 时执行
  ],
  "else": [
    // condition=false 时执行（else_invert=false）
    // 可为空
  ]
}
```

编译时：展开为 `then` 或 `else` 的步骤 + 等效的 `IfConditionOp` 跳转。

### 2.11 `call` — 子蓝图调用（宏展开）

```json
{
  "type": "call",
  "blueprint": "<表达式 → string — 蓝图 ID>",
  "with": {
    "<param_name>": "<表达式>"
  }
}
```

被调蓝图在当前解释器中加载，传入 `with` 参数（已求值），其 `steps` 内联展开到调用者序列中。

### 2.12 `log` — 日志（调试）

```json
{
  "type": "log",
  "level": "<\"info\"|\"warn\"|\"debug\">",
  "text": "<表达式 → string>"
}
```

→ 引擎层日志输出，不生成 AtomicOp。

---

## 3. 表达式语法

### 3.1 字面量（裸值，context-dependent 推断）

```
"minecraft:stone"          → string
42                         → int
[0, 64, 0]                 → pos
[-1, 0, 0], [1, 0, 0]     → list<pos> (在 array context)
```

### 3.2 变量引用

```json
{"$": "param_name"}         // 取参数或循环变量
{"$.field": ["$pos_var", "x"]}  // 取 pos 的分量 (x/y/z)
```

### 3.3 算术运算

```json
{"+": ["$a", "$b"]}         // pos + pos / int + int
{"-": ["$a", "$b"]}         // int - int
{"*": ["$a", "$b"]}         // int * int
```

### 3.4 列表操作

```json
{"size": "$list_var"}        // 取 list<pos> 或 list<string> 的长度 → int
```

### 3.5 比较运算（给 if 用）

```json
{"==": ["$a", "$b"]}        // → bool
{"!=": ["$a", "$b"]}
{">":  ["$a", 0]}
{"<":  ["$a", 10]}
{">=": ["$a", 0]}
{"<=": ["$a", 10]}
```

### 3.6 Map 查询

```json
{"get": ["$map_var", {"keyof": "$key_var"}]}
// key_var 如是 pos，自动 pos→string 转换
```

### 3.7 字符串格式化

```json
{"format": ["Build {} at {}", "$name", "$anchor"]}
// → "Build 殖民地市政厅 at (0,64,0)"
// 第一个参数是模板，剩余参数替换 {} 占位符
```

### 3.8 简单引用语法糖

当一个步骤字段只需要直接取变量值（无运算），可用字符串简写：

```
"$variable_name"           // 等价于 {"$": "variable_name"}
```

即：以 `$` 开头且无 `{` 的字符串 → 解释为变量引用。

---

## 4. 完整示例

### 4.1 建筑蓝图 `build:place_structure`

```json
{
  "id": "build:place_structure",
  "type": "blueprint",
  "params": {
    "offsets": "list<pos>",
    "blocks": "map<string,string>",
    "name": "string",
    "anchor": "pos"
  },
  "steps": [
    {
      "type": "for_each",
      "list": "$offsets",
      "var": "off",
      "steps": [
        {
          "type": "place",
          "at": {"+": ["$anchor", "$off"]},
          "block": {"get": ["$blocks", {"keyof": "$off"}]}
        }
      ]
    },
    {
      "type": "emit_event",
      "event": "build_complete",
      "data": {
        "building_name": "$name",
        "blocks_placed": {"size": "$offsets"}
      }
    }
  ]
}
```

### 4.2 建筑 JSON 引用蓝图

```json
{
  "id": "town_hall",
  "type": "building",
  "display_name": "殖民地市政厅",
  "category": "basic",
  "block_id": "wandscape:town_hall",
  "pattern": [
    [-1, 0, -1], [-1, 0, 0], [-1, 0, 1],
    [0, 0, -1],             [0, 0, 1],
    [1, 0, -1],  [1, 0, 0],  [1, 0, 1],
    [-1, 1, -1], [-1, 1, 1], [1, 1, -1], [1, 1, 1]
  ],
  "block_mapping": {
    "-1,0,-1": "minecraft:stone_bricks",
    "-1,0,0":  "minecraft:stone_bricks",
    "-1,0,1":  "minecraft:stone_bricks",
    "0,0,-1":  "minecraft:stone_bricks",
    "0,0,1":   "minecraft:stone_bricks",
    "1,0,-1":  "minecraft:stone_bricks",
    "1,0,0":   "minecraft:stone_bricks",
    "1,0,1":   "minecraft:stone_bricks",
    "-1,1,-1": "minecraft:oak_log",
    "-1,1,1":  "minecraft:oak_log",
    "1,1,-1":  "minecraft:oak_log",
    "1,1,1":   "minecraft:oak_log"
  },
  "comfort": 5,
  "magic": 3,
  "wonder": 2,
  "maintenance_cost": 4,
  "shutdown_penalty": { "output_reduction": 0.5, "time_multiplier": 2.0 },
  "queue": { "capacity": 5, "task_types": ["building"] },
  "unlock_requirement": { "min_wonder": 0 },
  "boundary": { "min": [-1, 0, -1], "max": [1, 1, 1] },
  "blueprint": {
    "id": "build:place_structure",
    "bind": {
      "offsets": "$pattern",
      "blocks":  "$block_mapping",
      "name":    "$display_name"
    }
  }
}
```

> `"bind"` 的值 = `$field_name` 裸变量引用（从 building JSON 字段取值后重命名为蓝图参数名）。enqueuer 硬编码注入 `anchor`。复杂表达式留在 DSL `steps` 内部。

### 4.3 带条件的采集蓝图

```json
{
  "id": "gather:smart_harvest",
  "type": "blueprint",
  "params": {
    "resource": "string",
    "target": "pos",
    "min_stock": "int"
  },
  "steps": [
    {
      "type": "if",
      "condition": "resource_below",
      "params": {
        "resource": "$resource",
        "threshold": "$min_stock"
      },
      "then": [
        {"type": "request_resource", "resource": "$resource", "amount": 10},
        {"type": "log", "level": "info", "text": {"format": ["Requesting {} from warehouse", "$resource"]}}
      ],
      "else": [
        {"type": "log", "level": "debug", "text": {"format": ["Stock of {} sufficient, skipping", "$resource"]}}
      ]
    }
  ]
}
```

---

## 5. Java 类型设计

### 5.1 core 层 — 值载体

使用 Gson `JsonElement` 作为参数值载体。core 允许 Gson 依赖（`CLAUDE.md` 只禁 MC 类）。

```java
// BlueprintSteps (core 层) — 签名升级
@FunctionalInterface
public interface BlueprintSteps {
    TaskSequence generate(Map<String, JsonElement> params);  // 改自 Map<String, String>
}

// TaskRequest
public record TaskRequest(
    String blueprintId,
    Map<String, JsonElement> params,  // 改自 Map<String, String>
    int priority
) {}

// WorkItem (shared/data)
public record WorkItem(
    String blueprintId,
    Map<String, JsonElement> params,  // 改自 Map<String, String>
    int priority
) {}
```

### 5.2 core 层 — DSL AST 类型

public record BlueprintDefinition(
    String id,
    Map<String, ParamType> params,
    List<StepNode> steps
) {}

public sealed interface ParamType {
    record StringType() implements ParamType {}
    record IntType() implements ParamType {}
    record PosType() implements ParamType {}
    record ListPosType() implements ParamType {}
    record ListStringType() implements ParamType {}
    record MapStringStringType() implements ParamType {}
}

public sealed interface StepNode {
    record PlaceStep(ExprNode at, ExprNode block) implements StepNode {}
    record RemoveStep(ExprNode at, ExprNode from) implements StepNode {}
    record ConvertStep(ExprNode at, ExprNode from, ExprNode to) implements StepNode {}
    record BlockInteractStep(ExprNode at, String action) implements StepNode {}
    record EntityInteractStep(ExprNode target, ExprNode effect, ExprNode strength, ExprNode duration) implements StepNode {}
    record RitualStep(ExprNode ritual, ExprNode at, ExprNode channelTicks) implements StepNode {}
    record RequestResourceStep(ExprNode resource, ExprNode amount) implements StepNode {}
    record EmitEventStep(ExprNode event, Map<String, ExprNode> data) implements StepNode {}
    record ForEachStep(ExprNode list, String var, List<StepNode> steps) implements StepNode {}
    record IfStep(ExprNode condition, Map<String, ExprNode> params, boolean elseInvert,
                  List<StepNode> thenSteps, List<StepNode> elseSteps) implements StepNode {}
    record CallStep(ExprNode blueprintId, Map<String, ExprNode> with) implements StepNode {}
    record LogStep(String level, ExprNode text) implements StepNode {}
}

public sealed interface ExprNode {
    record LiteralString(String value) implements ExprNode {}
    record LiteralInt(int value) implements ExprNode {}
    record LiteralPos(GridPos value) implements ExprNode {}
    record LiteralListPos(List<GridPos> value) implements ExprNode {}
    record LiteralListString(List<String> value) implements ExprNode {}
    record LiteralMap(Map<String, String> value) implements ExprNode {}
    record Var(String name) implements ExprNode {}
    record FieldAccess(ExprNode target, String field) implements ExprNode {}  // .x / .y / .z
    record Add(ExprNode left, ExprNode right) implements ExprNode {}
    record Sub(ExprNode left, ExprNode right) implements ExprNode {}
    record Eq(ExprNode left, ExprNode right) implements ExprNode {}
    record Neq(ExprNode left, ExprNode right) implements ExprNode {}
    record Gt(ExprNode left, ExprNode right) implements ExprNode {}
    record Lt(ExprNode left, ExprNode right) implements ExprNode {}
    record Gte(ExprNode left, ExprNode right) implements ExprNode {}
    record Lte(ExprNode left, ExprNode right) implements ExprNode {}
    record MapGet(ExprNode map, ExprNode key) implements ExprNode {}
    record Size(ExprNode target) implements ExprNode {}  // list<pos> / list<string> → int
    record Format(ExprNode template, List<ExprNode> args) implements ExprNode {}
    record KeyOf(ExprNode target) implements ExprNode {}  // pos→string via toKey()
}
```

---

## 6. 连锁变更清单

### 6.1 `WorkItem.params` — `Map<String,String>` → `Map<String, JsonElement>`

| 受影响的类 | 变更 |
|-----------|------|
| `WorkItem` record | 字段类型改 |
| `TaskRequest` record | 字段类型改 |
| `BlueprintSteps` | 签名改 `generate(Map<String, JsonElement>)` |
| `BlueprintRegistry.compile()` | 传递新类型 |
| `GlobalTask.taskParams` | 字段类型改 |
| `AbstractWandscapeBE` — NBT 持久化 | `JsonElement.toString()` → `TagParser.parse()` 存成 NBT，或 `putString(tag, json.toString())` |
| `WandscapeBuildingBlock.useWithoutItem()` | 构造 `JsonElement` params + bind rename + anchor 组装 |
| `BlockPlaceHandler` | 同上 |
| `BuildingTaskSource` | `TaskRequest` 构造类型更新 |
| `EventDrivenTaskSource.registerDefaultBlueprints()` | 旧 lambda 内部 `.getAsString()` 取值 |
| `EmitEventOp.templateParams` | **不改** — 仍为 `Map<String,String>`（`{{}}` 文本模板，独立用途） |

### 6.2 `BuildingConfig` 新增字段

```java
// BuildingConfig 新增 record
public record BlueprintRef(
    String id,                         // 蓝图 ID，如 "build:place_structure"
    Map<String, String> bind           // key=蓝图参数名, value="$field_name" 裸变量引用
) {}
```

`BuildingConfig` 新增字段：`@Nullable BlueprintRef blueprint`（null 表示无蓝图引用，兼容过渡期）。

### 6.3 新增文件

| 文件 | 位置 | 作用 |
|------|------|------|
| `BlueprintDefinition` | `core/task/` | 蓝图 JSON 解析后的 AST 根对象（id + params + steps） |
| `BlueprintInterpreter` | `core/task/` | DSL JSON → `BlueprintSteps` lambda 的运行时解释器 |
| `BlueprintConfigLoader` | `engine/source/blueprint/` | 从 `data/wandscape/blueprints/` 加载 + 注册到 `BlueprintRegistry` |
| `BlueprintConfigTest` | `test/` | 单元测试 |

---

## 7. 决策清单（全部完成）

| # | 议题 | 决策 |
|---|------|------|
| 1 | Building JSON `type` 字段 | 不加，靠文件夹区分 |
| 2 | `bind` 语法 | `$field_name` 裸变量引用 |
| 3 | enqueuer rename vs interpreter | enqueuer 做 rename，解释器单一职责 |
| 4 | anchor 构造 | 建筑 enqueuer 硬编码 `[x,y,z]` |
| 5 | 参数值载体 | `Map<String, JsonElement>`（core 加 Gson） |
| 6 | `BlueprintSteps` 签名 | `generate(Map<String, JsonElement>)` |
| 7 | `BlueprintRef` record | `(String id, Map<String,String> bind)` |
| 8 | `BlueprintConfigLoader` 位置 | `engine/source/blueprint/` |
| 9 | 旧 `DataDrivenSteps` | 删除 |
| 10 | 旧 `gather:*` lambda | 9→1 个 JSON + 参数区分 |
| 11 | 解释器错误处理 | 抛异常 → 任务 FAILED |
| 12 | `list` 长度表达式 | `{"size": "$list"}` → int |
| 13 | `for_each` 循环变量遮蔽 | 禁止同名，检测到抛异常 |
| 14 | `call` 循环递归检测 | 调用栈去重 `Set<String>` + UT |
| 15 | `call` 跨文件查找 | 全局 `BlueprintRegistry` |
| 16 | `call` 参数校验 | 严格：少了报错，类型对齐 |
| 17 | ID 命名空间 | 无强制前缀，约定即规则 |
| 18 | NBT 持久化 | `gson.toJson(params)` 整存字符串 |
