# Command 重构 · API 缺口与裸逻辑盘点

> 日期：2026-09-04
> 背景：重构 `/wandscape` 指令体系（玩家玩法指令 + 开发 `/wandscape test`）时，发现若干公开 API 仍是
> `@Unimplemented` 桩，导致部分指令要么**绕过 API 走内部/裸逻辑**，要么**没有实现**。
> 本文档记录这些缺口，供后续补 API 阶段参考。
>
> ## 状态更新（2026-09-04）
> **已闭合**：`NpcApi.spawnNpc`、`NpcAttributesApi.roll`、`TavernApi.recruitMage/chargeRecruit/recruitForColony`
> 及 `NpcSpawnSpec`（含 `fromCandidate`）。酒馆「招募 NPC」（付费掷点）与「简历招募」两条生成路径、
> `tavern recruit` 指令均已改走这些公开 API；`TavernRecruitPacket` 内联生成逻辑已移除。
> **已闭合**：`WarehouseApi` 补齐 `addItem/removeItem/clearItems/clearElements/clearAll`（+ `getItemCapacity/getUsedItemCapacity`）；
> `warehouse`/`element` 指令已改走 `WarehouseApi`（不再是裸 `ColonyItemBank`）。
> **已闭合**：`ColonyApi` 全部未实现方法补齐（`getColonyName/setColonyName/getMaxLevel/getExpToNext/isActive/setActive/setColonyLevel`）；
> `colony level/exp/name` 指令 + 内部全部 `setColonyName/setLevel/addExperience` 变更点 + `getLevel/getExperience/getColonyName/expToNext`
> 读取点（NpcApi/MageHut/Recipe/BuildUnlock/Interact/Status/Achievement/Tourist*）均改走 `ColonyApi`；`ColonyActivation` 加了 per-colony 强制冻结覆盖供 `setActive`。
> 仍裸逻辑/未做：见下文标「尚未」。
>
> 注：`WarehouseApi` 的公开增删清（`addItem/removeItem/clearItems/clearElements/clearAll`）只约束「colony 级、
> 无预留/无容量门控」的管理/整合包语义；模组内部的生产/建造/道路资源供给走 `ColonyResourceAccess`（`WarehouseManager`
> 实现，本就是仓库权威路径），涉及预留量/容量/播种标记等 bank 专属状态，故未强行改走公开 API（CLAUDE.md 硬规则 6：
> 纯内部直接调用，禁止包装成 API）。

## 一、裸逻辑（走了内部实现，因公开 API 是桩或太绕）

| 指令 | 落地实现 | 为什么不走公开 API | 建议 | 状态 |
|---|---|---|---|---|
| `warehouse` / `element` 的 `add`/`remove`/`clear` | **已改走** `WarehouseApi.addItem/removeItem/clearItems/clearElements/clearAll` | 原先裸 `ColonyItemBank` | 已闭合 | **已闭合** |
| `colony level` / `exp` / `name` | **已改走** `ColonyApi.setColonyLevel/grantExperience/setColonyName` | 原先直接 `ColonyLevelManager`（`setColonyLevel/setColonyName` 曾是 `@Unimplemented`） | 已闭合 | **已闭合** |
| `tavern recruit` | **已改走** `TavernApi.recruitForColony`（删了 `TavernRecruitPacket.recruitDirect`） | 原先 `recruitMage` 只取简历、不生成不扣费 | 已闭合 | **已闭合** |
| `tourist despawnAll` | `TouristApiImpl` 内 `discard()` + 清 shadow | 接口原为桩（本次顺手实现）；实现是纯实体操作 | 已实现 | 已闭合 |

## 二、没做 / 留白（API `@Unimplemented` 或太绕 — 按「难的先不做」暂缓）

| 想做的指令 | 卡点 | 相关桩 | 状态 |
|---|---|---|---|
| `warehouse capacity <n>`（调容量） | `ModConfigSpec.IntValue` 不能运行时 set；容量为派生值 | `Config.WAREHOUSE_ITEM_CAPACITY`（非 API 桩，是 NeoForge 配置限制） | 暂缓 |
| `npc revive <name>` | 需查死亡注册表按名解析 UUID | `NpcApi.reviveNpc(UUID)` 已实现，但名字→id 需要 `ColonyDeathRegistry` 查询入口 | 尚未 |
| `npc dismiss <name>` | 移除法师 API 是桩 | `NpcApi.removeNpc` `@Unimplemented` | 尚未 |
| `road build`（程序化建路） | 建路 API 是桩 | `RoadApi.addEdge` `@Unimplemented` | 尚未 |
| `npc train` / `levelUp` 指令 | 训练/升级 API 是桩 | `NpcAttributesApi.trainNpc`/`levelUpNpc` `@Unimplemented` | 尚未 |
| 跨殖民地元素转账指令 | 转账 API 是桩 | `WarehouseApi.transferElements` `@Unimplemented` | 尚未 |
| 生产/合成派单指令（如手动合成） | 派单 API 是桩 | `ProductionApi.enqueueSynthesize` `@Unimplemented` | 尚未 |
| 殖民地冻结/激活指令 | 激活 API 是桩 | `ColonyApi.setActive`/`isActive` `@Unimplemented` | 尚未 |

## 三、优先级建议

若后续补 API，按「指令可落地度」排序（本次已闭合生成/招募相关）：

1. **`WarehouseApi` 增删补清** —— 让 `warehouse`/`element` 指令从裸 bank 改走 API（改动小、收益大）。
2. **`ColonyApi.setColonyLevel`/`setColonyName`** —— `colony level/name` 指令转 API。
3. **`NpcApi.removeNpc` / 死亡注册查询** —— 解锁 `npc dismiss`/`npc revive`。
4. **`RoadApi.addEdge`** —— 解锁 `road build`。
5. 其余（`transferElements`/`enqueueSynthesize`/`setActive`/`trainNpc`）按玩法演进需要再补。

> 注：本次指令重构已按「能给公开 API 就公开、否则内部实现并在注释标明」处理，未新增任何无版本号的
> 存档字段或兼容别名（`despawnAll`/`spawnNpc`/`recruit*` 之外均不触及 SavedData 结构）。
> `ColonyCommand` 初始 builder 生成（冷启动建房+铁甲）为特殊引导逻辑，未走 `spawnNpc`（需按实体灌法杖/护甲）。
