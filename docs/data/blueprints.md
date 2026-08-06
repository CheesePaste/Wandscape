# 数据格式 — 蓝图 DSL JSON

位置：`src/main/resources/data/wandscape/blueprints/<name>.json`

解析：`engine/source/blueprint/BlueprintConfigLoader` → `task/engine/dsl/BlueprintInterpreter` 展开为 `TaskSequence`（AtomicOp 序列）。任务类型：构建/拆除/节点采集/地形/道路/生产。

## 顶层结构

```json
{
  "id": "build:place_structure",
  "display_name": "施工",
  "description": "…",
  "params": {
    "anchor": "pos",
    "offsets": "list<pos>",
    "blocks": "map<string,string>",
    "material_list": "map<string,string>",
    "name": "string",
    "amount": "int",
    "tiles": "list<string>"
  },
  "steps": [ ... ]
}
```

`params` 值是字符串类型声明：`"string"` / `"int"` / `"pos"` / `"list<pos>"` / `"list<string>"` / `"map<string,string>"`。

## steps 数组（StepNode 类型）

| type | 说明 | 关键字段 |
|---|---|---|
| `request_resource` | 请求资源 | `items`（用 `map_to_items` 从 map 生成）或 `dynamic_items` |
| `for_each` | 遍历 | `list`（表达式）/ `var` / `steps` |
| `place` | 放方块 | `at`（`{"+":[..]}` 相对锚点）/ `block` / `nbt`（`{"get":[..,"keyof"]}`） |
| `remove` / `convert` | 拆/换方块 | 同 place |
| `block_interact` | 交互方块 | `action`（gather/decompose/synthesize/craft_wand/brew_potion/toggle/activate/open_gui）/ `at` / `channel_ticks` / `params` |
| `emit_event` | 发 CustomEvent | `name` / `data`（`$name` 或 `{"size":..}`） |
| `log` | 日志 | `format` |
| `entity_interact` / `ritual` / `call` / `parallel` / `if` | 见 task/ 模块 | 递归子 steps |

## 表达式算子

`$变量糖`、算术（add/sub/mul）、比较（eq/neq/gt/lt/gte/lte）、`get` / `size` / `keyof` / `map_to_items` / `format`。隐式转换 int→string、pos→"x,y,z"。

## 示例：build_place_structure.json（节选）

```json
{
  "type": "request_resource",
  "items": {"map_to_items": [{"get": ["$material_list"]}]}
},
{
  "type": "for_each",
  "list": {"get": ["$offsets"]},
  "var": "offset",
  "steps": [
    {
      "type": "place",
      "at": {"+": [{"get": ["$anchor"]}, {"get": ["offset"]}]},
      "block": {"get": [{"keyof": [{"get": ["$blocks"]}, {"get": ["offset"]}]}, 0]},
      "nbt": ...
    }
  ]
},
{
  "type": "emit_event",
  "name": "build_complete",
  "data": {"$name": {"get": ["$name"]}, "size": {"size": [{"get": ["$blocks"]}]}}
}
```

## 现有蓝图（data/wandscape/blueprints/）

| 文件 | 蓝图 id | 用途 |
|---|---|---|
| build_clear_and_build.json | build:clear_and_build | 清空 + 建造（默认建筑任务） |
| build_place_structure.json | build:place_structure | 纯建造（修复任务也用） |
| build_demolish_structure.json | build:demolish_structure | 拆除 |
| node_gather.json | node:gather | 节点元素采集 |
| road_build_segment.json | road:build_segment | 道路分段建造 |
| terrain_fill_box.json | terrain:fill_box | 填方块盒 |
| terrain_flatten.json | terrain:flatten | 地形平整 |
| demo_tnt_platform.json | - | 演示 |
| production/brew_potion.json / craft_wand.json / decompose.json / synthesize.json | production:… | 生产动作 |
