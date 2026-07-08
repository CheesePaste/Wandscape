# 样条线模板 JSON 格式

位置：`config/wandscape/splines/*.json`

本 JSON 格式用于存储样条线路径几何模板，并以局部相对偏移量表示。

## 完整示例

```json
{
  "closed": false,
  "points": [
    {
      "ax": 0.0,
      "ay": 0.0,
      "az": 0.0,
      "cpx": -2.0,
      "cpy": 0.0,
      "cpz": 0.0,
      "cnx": 2.0,
      "cny": 0.0,
      "cnz": 0.0,
      "locked": true
    },
    {
      "ax": 10.0,
      "ay": 1.0,
      "az": 2.5,
      "cpx": 8.0,
      "cpy": 1.0,
      "cpz": 2.5,
      "cnx": 12.0,
      "cny": 1.0,
      "cnz": 2.5,
      "locked": true
    }
  ]
}
```

## 字段说明

### 1. 全局配置

| 键名 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `closed` | boolean | `false` | 样条线是否首尾相接形成闭合环路 |
| `points` | list\<object\> | `[]` | 样条线控制点列表 |

### 2. 节点配置 (SplinePointDto)

所有坐标偏移量都是**相对第一个锚点（索引为 0 且偏移恒为 `0.0`）**的局部 3D 浮点偏移。

| 键名 | 类型 | 说明 |
|------|------|------|
| `ax`, `ay`, `az` | double | 锚点 (Anchor Point) 相对原点的 3D 偏移 |
| `cpx`, `cpy`, `cpz` | double | 前控制手柄 (Prev Control Handle) 相对原点的 3D 偏移 |
| `cnx`, `cny`, `cnz` | double | 后控制手柄 (Next Control Handle) 相对原点的 3D 偏移 |
| `locked` | boolean | 控制手柄是否锁定镜像对称（`true` 则两端柄始终共线且长度相等） |
