# 模块依赖关系

## 依赖规则（编译时强制）

```
每个模块的 build.gradle 只允许依赖：
  - 01-shared-api（必须）
  - 08-building-core（可选，仅建筑类模块）
  - 禁止依赖 02-07 或 09-16 中的任何其他模块
```

## 依赖图

```
                    ┌──────────────────────┐
                    │   01-shared-api      │  ← 接口/事件/类型（全模块依赖）
                    └──────────┬───────────┘
                               │
          ┌────────────────────┼────────────────────┐
          │                    │                    │
          ▼                    ▼                    ▼
     ┌─────────┐         ┌─────────┐          ┌─────────┐
     │ 02 wand  │         │ 03 elem │          │ 08 bldg │
     │  system  │         │  system │          │  core   │  ← 建筑基类（08 仅依赖 01）
     └─────────┘         └─────────┘          └────┬────┘
          │                    │                    │
          │                    │    建筑基类/队列/    │
          │                    │    维护/三数值       │
          │                    │                    │
┌─────────┼────────────────────┼────────────────────┼──────────┐
│         │                    │                    │          │
▼         ▼                    ▼                    ▼          ▼
04         05                   06                   07         09
warehouse  atomic              task                 NPC       node
           ops                system              system     building
│         │                    │                    │          │
└─────────┴────────────────────┴────────────────────┴──────────┘
     (所有模块仅依赖 01 + 08(可选)。08 自身仅依赖 01)

┌──────────┬──────────┬──────────┬──────────┬──────────┐
│          │          │          │          │          │
▼          ▼          ▼          ▼          ▼          ▼
10          11         12         13         14         15
production  housing   tavern     ritual      mgmt      colony
stations   +pool      recruit    altar       panel     lifecycle
│          │          │          │          │          │
└──────────┴──────────┴──────────┴──────────┴──────────┘
                  (均依赖 01 + 08)

                                                    ▼
                                                   16
                                                 config
```

## 各模块依赖清单

| 模块 | 依赖 01 | 依赖 08 | 备注 |
|------|---------|---------|------|
| 02 wand-system | ✅ | ❌ | 独立模块，仅法杖物品+ NBT |
| 03 element-system | ✅ | ❌ | 独立模块，元素定义+映射 |
| 04 warehouse-system | ✅ | ✅ | 仓库是建筑 → 需 08 的 BE 基类 |
| 05 atomic-operations | ✅ | ❌ | 执行器通过 API 调仓库/建筑，不直接依赖 |
| 06 task-system | ✅ | ❌ | 调度器通过 API 调 NPC/建筑，不直接依赖 |
| 07 npc-system | ✅ | ❌ | 独立模块 |
| 08 building-core | ✅ | — | 唯一只依赖 01 的核心模块 |
| 09 node-building | ✅ | ✅ | 节点是建筑 → 扩展 AbstractWandscapeBE |
| 10 production-stations | ✅ | ✅ | 制作站/工作站是建筑 → 扩展 BE |
| 11 housing-mana-pool | ✅ | ✅ | 房屋/魔力池是建筑 → 扩展 BE |
| 12 tavern-recruitment | ✅ | ✅ | 酒馆是建筑 → 扩展 BE |
| 13 ritual-altar | ✅ | ✅ | 祭坛是建筑 → 扩展 BE（多方块） |
| 14 management-panel | ✅ | ✅ | 面板通过 API 管理建筑 |
| 15 colony-lifecycle | ✅ | ✅ | 殖民地的市政厅是建筑 |
| 16 data-driven-config | ✅ | ❌ | JSON 加载框架，不涉及建筑逻辑 |

## 模块间通信方式

```
模块 A ──→ 需要触发其他模块行为 ──→ post(Event) 到 NeoForge.EVENT_BUS
模块 A ──→ 需要查询其他模块数据 ──→ WandscapeApis.getXxxApi().query()
```

**禁止**：模块 A 直接 `import com.wsteam.wandscape.<模块B>.*`（除非 B 是 01 或 08）。

## 新增模块检查清单

1. 在 01-shared-api 中定义该模块的 API 接口
2. 在 WandscapeApis 中添加 getter/setter
3. 若为建筑类模块：继承 AbstractWandscapeBE
4. 在本文件中添加一行依赖清单
5. 在 00-overview.md 中添加包路径
