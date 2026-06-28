# blueprint/editor/ — 蓝图节点编辑器

可视化节点图编辑器，用于创建和编辑蓝图 DSL（BlueprintDefinition）。
仿 UE Blueprints：步骤节点 + 表达式节点统一画布，执行流 + 数据流双线系统。

## 关键类

### 客户端状态

- **BlueprintEditorClientState** — volatile 静态状态：当前画布 CanvasGraph、脏标记、选中节点 ID、搜索浮窗文本、剪贴板。模式进入/退出管理（类似 BuildingEditorClientState）。
- **BlueprintEditorCanvas** — CanvasGraph 画布模型：nodes + execEdges + dataEdges + params。包含 CanvasGraph ↔ BlueprintDefinition JSON 双向转换器。
- **BlueprintNodeDefinition** — 节点描述符注册表：37 种节点（14 StepNode + 22 ExprNode + Input），每种声明 exec 引脚 + data 引脚 + 颜色 + 显示名。渲染函数遍历描述符统一绘制。

### 渲染与交互

- **BlueprintEditorImGui** — ImGui 渲染主类：
  - 画布（NodeEditor Canvas）：步骤节点（大矩形，带执行流横条）、表达式节点（小菱形/紧凑矩形）、Input 节点（仅数据输出）
  - 连线：执行流 = 白色脉冲线，数据流 = 按类型着色（pos=绿，string=蓝，int=黄，list=橙，map=紫）
  - Inspector 面板（右侧 250px，可折叠）：选空白=蓝图元数据+params 列表，选节点=该节点属性字段
  - 搜索浮窗（右键画布）：输入过滤 37 种节点类型，回车创建
  - 顶栏：可编辑 ID / DisplayName / Description
  - 快捷键：P=Place, V=Var, F=ForEach, Delete=删除选中
- **BlueprintEditorController** — 逻辑：新建/加载/保存/删除/连线验证/表达式类型检查
- **BlueprintEditorNetwork** — 网络包：BlueprintSavePacket（发送 BlueprintDefinition JSON 到服务端保存为 `data/wandscape/blueprints/*.json`）

### 集成

- **ImGuiManager** — renderBlueprintEditor() 委托给 BlueprintEditorImGui.render()（类似 BuildingEditorImGui 模式）
- **BlueprintEditorCommand** — `/wandscape blueprinteditor` 切换编辑器

## 节点类型速查

### 步骤节点（14 种，大矩形，有执行流横条）

| type | 颜色 | exec pins | data pins |
|------|------|-----------|-----------|
| Place | 绿 | in, out | at(pos), block(string), consumable(string?) |
| Remove | 绿 | in, out | at(pos), from(string) |
| Convert | 绿 | in, out | at(pos), from(string), to(string) |
| BlockInteract | 橙 | in, out | at(pos), action(enum), params(map), chTicks(int), mana(int) |
| EntityInteract | 橙 | in, out | target(string), effect(string), strength(int), duration(int) |
| Ritual | 橙 | in, out | ritual(string), at(pos), params(map) |
| RequestResource | 黄 | in, out | items(dynamic list), dynamicItems(expr) |
| EmitEvent | 红 | in, out | event(string), data(map) |
| ForEach | 蓝 | in, loop_body→, completed→ | list(expr), var(string) |
| If | 蓝 | in, then→, else→, completed→ | condition(string), params(map), elseInvert(bool) |
| Call | 紫 | in, out | blueprintId(string), with(map) |
| Parallel | 蓝 | in, branch1→, branch2→, ..., completed→ | (dynamic branch exec outs) |
| Log | 灰 | in, out | level(enum), text(string) |

### 表达式节点（22 种，小菱形/紧凑矩形，无执行流）

| type | 颜色 | 输入 | 输出类型 |
|------|------|------|---------|
| LiteralString | 浅蓝 | 0 (inline edit) | string |
| LiteralInt | 浅黄 | 0 (inline edit) | int |
| LiteralPos | 浅绿 | 0 (inline edit) | pos |
| LiteralListPos | 浅橙 | 0 (inline edit) | list\<pos\> |
| LiteralListString | 浅橙 | 0 (inline edit) | list\<string\> |
| LiteralMap | 浅紫 | 0 (inline edit) | map\<string,string\> |
| Var | 浅蓝 | 0 (dropdown) | dynamic |
| FieldAccess | 绿 | 1 in(pos) | int |
| Add | 深绿 | 2 in(any, any) | pos/int |
| Sub | 深绿 | 2 in(int, int) | int |
| Mul | 深绿 | 2 in(int, int) | int |
| Eq | 深黄 | 2 in(any, any) | bool |
| Neq | 深黄 | 2 in(any, any) | bool |
| Gt | 深黄 | 2 in(any, any) | bool |
| Lt | 深黄 | 2 in(any, any) | bool |
| Gte | 深黄 | 2 in(any, any) | bool |
| Lte | 深黄 | 2 in(any, any) | bool |
| MapGet | 深紫 | 2 in(map, key) | string |
| Size | 深紫 | 1 in(list) | int |
| Format | 浅紫 | template(string) + dynamic args | string |
| KeyOf | 浅绿 | 1 in(pos) | string |
| MapItems | 深紫 | list(list), resource(expr), amount(expr) | list\<item\> |

### Input 节点（每个蓝图 param 自动生成一个）

| 颜色 | 输出类型 |
|------|---------|
| 按类型着色 | 对应 ParamType 的输出类型 |

## 数据流

```
用户操作（创建节点/连线/编辑属性）
  → BlueprintEditorImGui 更新 CanvasGraph
  → BlueprintEditorClientState 持有最新 CanvasGraph
  → 保存：BlueprintEditorCanvas.toDefinition() → BlueprintDefinition
  → BlueprintEditorNetwork → 服务端 → JSON 文件
  → 加载：JSON 文件 → BlueprintDefinition → BlueprintEditorCanvas.fromDefinition() → CanvasGraph
```

## 连线验证规则

- **执行流**：步骤节点之间，exec out → exec in，无类型约束
- **数据流**：表达式节点输出 → 步骤节点数据输入（或表达式节点输入），类型必须兼容（pos→pos, string↔int 隐式转换, pos→string 隐式转换）
- **循环体**：ForEach 的 Loop Body exec out → 子步骤 exec in，DFS 收集所有子步骤
- **分支**：If 的 Then/Else exec out → 分支子步骤 exec in，各自独立 DFS

## 依赖

- `imgui/` — ImGui 渲染基础设施
- `core/task/` — BlueprintDefinition / StepNode / ExprNode / ParamType（DSL AST）
- `shared/` — WandscapeApis + 事件
- 纯客户端包，不参与服务端编译

## 与 core/ 的边界

blueprint/editor/ 可以直接 import core/task/ 的 sealed record 类型（ExprNode、StepNode 等是纯 Java record，零 MC 依赖），无需通过 shared/api 防腐层。这是少数几个允许跨层直接引用的例外——因为 core/task/ 本身是纯数据 AST，不持有任何运行时状态。
