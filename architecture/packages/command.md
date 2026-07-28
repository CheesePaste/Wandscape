# command/ — 调试命令

全部在 `/wandscape` 命名空间下，OP 权限(level 2)。

| 子命令 | 功能 |
|--------|------|
| fillNode | 填充节点建筑队列（测试用） |
| mana | NPC 魔力调试开关 |
| navTest | 导航测试 |
| publishBlueprint | 手动发布蓝图任务 |
| recover clear/status | 清空所有任务+恢复 / 显示任务池状态 |
| road info/rebuild/edit | 路网统计 / 全量 MST 重建 / 切换编辑器 |
| roadTest | 道路生成测试 |
| stressTest | 压力测试 |

## 依赖

- engine/WandscapeEngine
- core/task/GlobalTaskPool
