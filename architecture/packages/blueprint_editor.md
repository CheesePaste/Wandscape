# blueprint/editor/ — 蓝图节点编辑器

仿 UE Blueprints 的可视化节点图编辑器。步骤节点 + 表达式节点统一画布，执行流 + 数据流双线系统。

## 数据流

```
用户操作（创建节点/连线/编辑属性）
  → BlueprintEditorImGui 更新 CanvasGraph
  → client state 持有最新 CanvasGraph
  → 保存：CanvasGraph.toDefinition() → BlueprintDefinition → 服务端 → JSON 文件
  → 加载：JSON → BlueprintDefinition → CanvasGraph.fromDefinition() → CanvasGraph
```

## 连线验证规则

- **执行流**：步骤节点间无类型约束
- **数据流**：类型必须兼容（pos→pos, string↔int 隐式转换）
- **循环体**：ForEach Loop Body → DFS 收集子步骤
- **分支**：If Then/Else → 独立 DFS

## 节点类型速查

### 步骤节点（14 种）

Place/Remove/Convert（绿）/ BlockInteract/EntityInteract/Ritual（橙）/ RequestResource（黄）/ EmitEvent（红）/ ForEach/If/Parallel（蓝）/ Call（紫）/ Log（灰）

### 表达式节点（22 种）

字面量（6：string/int/pos/list\<pos\>/list\<string\>/map）+ Var + FieldAccess + 运算符（Add/Sub + Eq/Neq/Gt/Lt/Gte/Lte）+ MapGet/Size/Format/KeyOf/MapItems

### Input 节点

按 ParamType 自动生成，类型按参数着色。

## 依赖

- `imgui/` — ImGui 渲染基础设施
- `core/task/` — BlueprintDefinition / StepNode / ExprNode / ParamType（DSL AST，纯 Java record 可跨层直接引用）
- 纯客户端包，不参与服务端编译
