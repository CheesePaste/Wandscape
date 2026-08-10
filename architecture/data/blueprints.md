# 蓝图 DSL 格式

位置：`data/wandscape/blueprints/*.json`

## 完整示例

```json
{
  "id": "build:place_structure",
  "display_name": "Build Structure",
  "description": "放置建筑结构的所有方块",
  "params": {
    "offsets": "list<pos>",
    "blocks": "map<string,string>",
    "name": "string",
    "anchor": "pos"
  },
  "defaults": {
    "entities": []
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
        "blocks_placed": {"size": "$offsets"},
        "anchor": "$anchor"
      }
    }
  ]
}
```

`defaults`（可选）：声明可选参数的默认值，调用方省略时解释器自动填入（`interpret` 入口与 `call` 步骤的 callee 上下文都生效）；显式传入优先。`build:place_structure`/`build:clear_and_build` 的 `entities` 用它缺省为空数组——旧建筑/修复任务没有装饰实体也不报错。

## 参数类型 (ParamType)

| 类型 | 说明 |
|------|------|
| string | 字符串 |
| int | 整数 |
| pos | 位置 [x,y,z] JSON 数组 |
| list\<pos\> | 位置数组 |
| list\<string\> | 字符串数组 |
| map\<string,string\> | 字符串映射 |
| list\<map\<string,string\>\> | JSON 对象数组（装饰实体等，`for_each` 可遍历） |

## 步骤类型 (12 种)

| type | 说明 |
|------|------|
| place | 放置方块。at=位置表达式，block=方块表达式 |
| remove | 移除方块 |
| convert | 转换方块（源→目标） |
| block_interact | 方块交互（toggle/activate/open_gui） |
| entity_interact | 实体交互（apply effect） |
| ritual | 仪式（ritual+target，channelTicks硬编码） |
| request_resource | 请求资源 |
| emit_event | 发出领域事件 |
| for_each | 循环展开。list+var+steps |
| if | 条件分支。condition+then+else |
| call | 调用子蓝图 |
| log | 日志输出（调试用） |

## 表达式类型 (21 种，ExprNode sealed interface)

**字面量(6)**：StringLiteral/IntLiteral/PosLiteral/ListLiteral/MapLiteral/NullLiteral

**变量(2)**：Var($name) / FieldAccess(base, field)

**运算符(5)**：Add(+) / Sub(-) / Mul(*) / Div(/) / Mod(%)

**比较(3)**：Eq / Lt / Gt

**集合(3)**：MapGet / Size / KeyOf

**其他(2)**：Format / IfExpr

隐式类型转换：int↔string，pos数组↔list\<pos\>

## 现有蓝图

- `build:place_structure` / `build:clear_and_build` — 建筑放置
- `node:gather` — 节点采集
- `road:build_segment` / `road:build_decoration` — 道路
- `terrain:fill_box` — Road Fill 模式:填满两角包围的立方体（Replace 用 `road:build_segment`，Destroy/Fill 用 `terrain:flatten`）
- `demo:tnt_platform` — 测试用
- `production/decompose` / `synthesize` / `craft_wand` / `brew_potion` — 工作站配方

## 调用方式

BuildingConfig 的 `blueprint.bind` 将 JSON 字段名映射为蓝图参数。例如 `"offsets": "$pattern"` 把 building JSON 的 pattern 数组传给蓝图。
