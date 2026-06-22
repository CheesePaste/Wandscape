# command/ — 调试命令

全部在 `/wandscape` 命名空间下，需要 OP 权限(level 2)。

| 子命令 | 功能 |
|--------|------|
| fillNode | 填充节点建筑队列（测试用） |
| mana | NPC 魔力调试开关 |
| navTest | 导航测试 |
| publishBlueprint | 手动发布蓝图任务 |
| road info | 路网统计（节点数/边数/状态/总长度） |
| road rebuild | 触发全量 MST 重建（权限 2） |
| road edit | 切换道路编辑器模式（客户端渲染+交互） |
| roadTest | 道路生成测试 |
| stressTest | 压力测试 |

### 道路编辑器 (road edit)

客户端功能，依赖 `road/client/` 下渲染器和网络包：

- 宽面色块渲染（状态色码：绿=完成/琥珀=建造中/蓝=规划中/红=悬浮高亮）
- 节点线框渲染（建筑白/路口紫/孤儿灰）
- 路径规划：右键选起点→右键地面加路径点→右键另一节点暂挂终点→Enter确认
- 实时预览：青蓝色半透明路面沿规划路径渲染
- 左键悬浮边→拆除（即时清空该边所有放置方块→移除边→同步全编辑玩家）
- Backspace 撤销、Escape 取消

## 依赖

- engine/WandscapeEngine
- core/task/GlobalTaskPool
