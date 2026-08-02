# 🚁 俯瞰选建与旋转编辑器（Overview Mode）API 级详细指南

Overview 模式为玩家提供 RTS 视角的鸟瞰施工体验。

![Overview 视角示意图](wandscape:textures/gui/guide/overview_diagram.png =200x100)

---

## 📖 UI 控件与字段 API 级别明细字典 (UI Options API Reference)

| 控件标识 / 标签 | 类型 / 范围 | 默认值 | 详细作用机制与计算影响 |
| :--- | :--- | :--- | :--- |
| **`Top Bar Help Button`** (`?`) | SkinButton | — | 位于 V 面板顶栏右上角的问号帮助按钮。在光标解封状态下点击打开当前模式的指南视窗。 |
| **`H / F1 Hotkey`** | Keybind | `H / F1` | 快捷键。面板开启时按下直接唤出对应功能的 Markdown 帮助指南。 |
| **`Blueprint Carousel`** | Carousel | — | 选建蓝图轮播卡片列，展示已解锁可建造的蓝图。双击蓝图确认选择进入施工状态。 |
| **`Rotation Controls`** | Button / Key | `0°` | 旋转建筑线框朝向（`0°`, `90°`, `180°`, `270°`），自动对齐 4 方向楼梯与门。 |
| **`Build Speed Slider`** | Slider (`1x ~ 5x`) | `1x` | 施工加速调节拉条。调节 NPC 建造放置方块的频次倍率。 |
| **`Demolish Button`** | MedievalButton | — | 拆除按钮。选定已有建筑安全无损回收资源并拆除结构。 |

---

👉 [跳转至 建筑扫描器指南](guide:scanner_guide)  
👉 [返回主测试页](guide:test_guide)
