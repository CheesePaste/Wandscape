# compass/ — 魔法指南针模块

`src/main/java/com/wsteam/wandscape/compass/`

## 职责

玩家侧物品「魔法指南针」三件套（smallitems 6/7/8）：指针始终指向玩家自己殖民地的市政厅；高级/终极在 tooltip 显示市政厅坐标；终极右键传送到市政厅。

## 规则（用户拍板）

- **档位**（`CompassTier`）：BASIC（合成站 1 级，仅指向）/ ADVANCED（10 级，+tooltip 坐标）/ ULTIMATE（20 级，+右键传送）。
- **归属限制**：市政厅坐标按「玩家自己创建殖民地」解析（`ColonyApi.getColonyByFounder` + `RaidTownHall.findTownHall`，要求 category=government 且结构完整）；无殖民地/无市政厅玩家指针乱转、终极传送给出上屏提示。
- **Curios 护符槽位未接入**（本期明确延后，见 `docs/gaps.md`）；贴图为占位（vanilla 指针帧 + 圆盘三色染色，`tools/generate_compass_textures.py` 生成）。

## 指针机制

- 客户端 `ItemProperties.register(item, "angle", new CompassItemPropertyFunction(lv, stack, ent -> CompassTargetClientCache.get()))` —— 复用 vanilla `CompassItemPropertyFunction` 的角度计算。
- 物品模型复用 vanilla `compass.json` 的 32 帧 `angle` override，指向 `compass_frame/<tier>/compass_XX`（圆盘按档位染色、针保留原版）。无目标（cache 为 null）时指针随机乱转，与 vanilla 无 lodestone 同义。
- 市政厅坐标服务端权威：`CompassService.resolveTownHall` → `CompassTargetPacket`(playToClient) → 客户端 `CompassTargetClientCache`。登录 / 切换维度（`CompassSyncHandler`）+ 物品 `inventoryTick` 节流（每 100 tick）重发，覆盖中途新建/重建市政厅。

## 代码结构

| 类 | 职责 |
|---|---|
| `CompassTier` | 档位 enum：itemId / minColonyLevel / themeColor（占位染色）/ showsCoords / canTeleport |
| `MagicCompassItem` | 物品：tooltip 显示说明+坐标、右键传送/重同步、inventoryTick 节流重发、use/useOn 路由 |
| `CompassService` | 服务端：resolveTownHall / syncFor / teleportToTownHall（安全落点复用盟誓戒指同款判定） |
| `CompassSyncHandler` | 登录 / 切换维度时 syncFor |
| `network/CompassTargetPacket` | S→C 同步 `GlobalPos`（hasTarget + target），复用 `GlobalPos.STREAM_CODEC` |
| `client/CompassTargetClientCache` | 客户端缓存目标（与 `OathRingClientData` 同范式），登出清空 |

## 依赖

- `raid/RaidTownHall.findTownHall(UUID)`：市政厅定位。
- `WandscapeApis.getColonyApiSilently().getColonyByFounder(uuid)`：玩家殖民地。
- 合成站配方：`data/wandscape/craft_recipes/*.json`，`type:"misc"`（合成站通用「扣元素产出物品」，type 不参与合成机制分发，见 recipe-unify 计划）。
