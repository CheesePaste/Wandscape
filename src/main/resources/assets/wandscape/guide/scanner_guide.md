# 🏗️ 建筑扫描器（Building Scanner）傻瓜式教学指南

建筑扫描器（Scanner）是创作者用于扫描游戏内建造好的建筑并**一键导出模组结构 JSON 蓝图**的开发工具。

![扫描器结构示意图](wandscape:textures/gui/guide/scanner_diagram.png =200x100)

---

## 1. 🚀 3 步傻瓜式操作流程

### 第一步：放置扫描器方块
1. 在选定的建筑角落放置一个【建筑扫描器方块】。
2. 右键打开扫描器 UI 面板。

### 第二步：框选 3D 边界（Boundary）
1. 在 `Min (X, Y, Z)` 与 `Max (X, Y, Z)` 中填入相对偏移坐标。
2. 在 `Category` 下拉菜单中选择建筑类别（例如 `node` 节点、`shop` 商店、`government` 市政厅等）。
3. 如果是特定类别，对应配置栏会自动展开：
   - `node`：在 `Node Config` 中设置默认产出的元素。
   - `shop`：在 `Shop Goods` 中配置允许销售的商品。
   - 维护费：在 `Maintenance Cost` 中挑选维护所需的元素。

### 第三步：一键导出 JSON
1. 点击底部的 **【Export JSON】** 按钮。
2. 文件将自动保存在 `.minecraft/wandscape/exports/<id>.json`。
3. 将该 JSON 放入数据包 `data/wandscape/buildings/` 即可完成模组蓝图制作！

---

## 🛠️ 常见问题排查（Troubleshooting & FAQ）

### Q1: 导出的蓝图建造时发现扫描器方块也被建出来了？
- **原因**：早期版本会扫描扫描器本身。
- **解决**：最新版 Scanner 导包时已**自动过滤扫描器方块本身**，无需手动擦除。

### Q2: 旋转放置建筑时，楼梯或门朝向错乱？
- **原因**：未使用带有 NBT 和 BlockState 朝向的标准 BlockData。
- **解决**：确保扫描时使用的游戏版本为 NeoForge 1.21.1+，扫描器会自动补全 4 方向 BlockState 的 `facing` 对齐数据。

### Q3: 提示 "Colony config not found"?
- **解决**：请确认 `Category` 是否选择了 `government`（市政厅类）而非硬编码的配置 ID。

---

👉 [跳转至 俯瞰选建与旋转指南](guide:overview_guide)  
👉 [返回主测试页](guide:test_guide)
