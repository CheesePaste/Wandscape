# /wandscape 指令手册

本页系统介绍模组全部 `/wandscape` 指令。玩法指令面向玩家，按域分组；开发调试指令统一藏在 `/wandscape test` 下（仅管理员可见）。

> 权限说明：
> - **免权限**：只读查询、救命类指令，任何玩家可用。
> - **op-2（管理员）**：改变殖民地资产/数值的指令，需要管理员权限。
> - **test（开发者）**：影响小或费解的调参/测试指令，仅管理员，且普通玩家补全里看不到。

## 小镇 Colony

- `/wandscape colony status` —— 小镇等级/经验/三值/游客/法师/元素/在建概览（免权限）
- `/wandscape colony list` —— 列出全部已注册小镇（免权限）
- `/wandscape colony create <名称>` —— 在面前创建小镇并生成初始法师（op-2）
- `/wandscape colony destroy` —— 销毁执行者所属小镇（op-2）
- `/wandscape colony level <等级>` —— 直接设小镇等级（op-2，经验清零）
- `/wandscape colony exp <数量>` —— 授予小镇经验（op-2，可能触发升级）
- `/wandscape colony name <名称>` —— 重命名小镇（op-2）

## 元素 Element

- `/wandscape element view` —— 查看 7 种元素存量（免权限）
- `/wandscape element add <类型> <数量>` —— 增加元素（op-2，土/木/水/火/金/风/暗）
- `/wandscape element remove <类型> <数量>` —— 减少元素（op-2）
- `/wandscape element clear` —— 清空元素（op-2）

## 仓库 Warehouse（只针对物品）

- `/wandscape warehouse view` —— 物品数量/占用/容量（免权限）
- `/wandscape warehouse add <物品id> <数量>` —— 增加物品（op-2）
- `/wandscape warehouse remove <物品id> <数量>` —— 减少物品（op-2）
- `/wandscape warehouse clear` —— 清空物品（op-2）

## 建筑 Building

- `/wandscape building list [分类]` —— 列出小镇建筑（免权限；分类可选 government/storage/production/node 等）
- `/wandscape building cancel <建筑id>` —— 取消在建建筑并退还材料（op-2，id 可用短前缀）
- `/wandscape building demolish <建筑id>` —— 拆除建筑（op-2）

## 道路 Road

- `/wandscape road status` —— 路网段数/建成状态/铺装总长（免权限）
- `/wandscape road cancel <路段id>` —— 撤回在建路段并退还材料（op-2）

## 法师 NPC

- `/wandscape npc list [idle]` —— 法师名单（等级/空闲/任务/血/蓝/法术；免权限）
- 法师的招募 → `/wandscape tavern recruit`（op-2）；训练/升级在法师小屋界面完成。

## 游客 Tourist

- `/wandscape tourist list` —— 游客名单（状态/等级/三需求条；免权限）
- `/wandscape tourist clear` —— 清空小镇游客（免权限，触发正常离城；游客卡死/过多时使用）

## 酒馆 Tavern

- `/wandscape tavern list` —— 当前待招法师简历（免权限）
- `/wandscape tavern recruit` —— 招募一名法师（op-2；首次免费，之后每种元素 10000）

## 守卫 Guard

- `/wandscape guard status` —— 守卫区数量/最近威胁/活跃守卫任务（免权限）

## 恢复 Recovery

- `/wandscape recover status` —— 任务池/建筑队列状态（免权限）
- `/wandscape recover clear` —— 清空任务与建筑队列、重置法师（免权限，**卡死前最后防线**；会中断所有进行中任务）

## 指南 Guide

- `/wandscape guide [页名]` —— 打开指南书（免权限；默认本页，可指定任意指南页如 `warehouse`）

## 开发者 test（仅管理员）

- `/wandscape test log ...` —— 运行时日志级别/过滤配置
- `/wandscape test profile ...` —— Tick 剖析录制
- `/wandscape test audit_elements` —— 审计缺失元素映射的物品
- `/wandscape test generate_element_mappings` —— 重新生成元素映射文件
- `/wandscape test fill <类型> <间距> <数量>` —— 注册一排建筑并派发施工任务
- `/wandscape test publish <蓝图id> [key=value ...]` —— 向任务池发布蓝图任务
- `/wandscape test magic ...` —— 无CD/无耗蓝/清CD/满蓝/对玩家施法
- `/wandscape test transport ...` —— 物品飞行动画/寻路压测
- `/wandscape test tourist spawn|state|cooldown` —— 强制生成游客/切状态/冷却开关
- `/wandscape test tavern spawn_mage|add_resume` —— 生成满条法师/直接注入简历
- `/wandscape test roadstudio` / `spline` —— 进入道路工作室/样条编辑器

---

[返回指南首页](index_guide.md)
