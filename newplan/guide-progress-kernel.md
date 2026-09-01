# GuideProgress 系统内核错置在 items —— 已拍板并落地 Step 1

> 2026-09-01 包扫描发现：`items` 域定位 = 纯薄物品容器（无系统内核），而 GuideProgress 是完整**跨域新手引导系统内核**。
> 2026-09-02 用户拍板 + 执行：
> - **命名分离**：概念 A「新手引导（onboarding）」→ `Tutorial`；概念 B「指南书（玩家翻的手册物品）」→ `Guidebook`。两者本无任何关系，共用 `Guide*` 词根致混淆（`guide` vs `guidance`、`GuideStep` vs `GuideScreen`）。
> - **A 独立成 `content/tutorial` 功能域**（`guide-progress-kernel.md` 方案一），迁出 items。
> - **范围**：全链一次性（Tier 2，类名+包+网络 id+lang key）。

## 概念 A：新手引导（tutorial）

服务端权威算"玩家走到第几步"（10 步：市政厅→仓库→存→工作站→合成→铺路→面包店→节点→祭坛→旅舍入住），聚合五域状态，推给客户端渲染。**已迁入 `content/tutorial`，类 `Guide*`→`Tutorial*`**：
- `content/tutorial/service/` —— `TutorialProgressService`、`TutorialServerContext`
- `content/tutorial/data/` —— `TutorialProgressSavedData`
- `content/tutorial/network/` —— `TutorialProgressSyncPacket`、`TutorialProgressUpdatePacket`
- `api/TutorialApi`（原 `GuideProgressApi`；**删除了混进来的 `openGuide`**——那是 B 指南书的操作，见下方）
- `foundation/ui/tutorial/` —— `TutorialStep`、`TutorialRegistry`、`TutorialRenderer`、`TutorialSession`
- 网络 id `guide_progress_*`→`tutorial_progress_*`；SavedData 名 `wandscape_guide_progress`→`wandscape_tutorial_progress`（开发期断档）；lang `guide.wandscape.*`→`tutorial.wandscape.*`
- `WandscapeApis.getGuideProgressApiSilently`→`getTutorialApiSilently`；`GUIDE_FOLD_TOGGLE`(Tab 折叠键)→`TUTORIAL_FOLD_TOGGLE`（key id `key.wandscape.guide_fold` 保留，仅改显示名——用户拍板）

## 概念 B：指南书（guidebook）—— Step 2 待做，尚未动

玩家右键打开的可合成手册物品（`GuideBookItem`）+ markdown 阅读器 + `assets/wandscape/guide/**`（52 md）。纯物品零系统内核，**留在 `content/items/guidebook`**（packages.md 早已判"guidebook→并入 items"）。Step 2 统一词根：
- `foundation/ui/guide/GuideScreen`→`foundation/ui/guidebook/GuidebookScreen`
- `GuideDocOpenPacket`→`items/guidebook/network/GuidebookDocOpenPacket`（id `guide_doc_open`→`guidebook_doc_open`）
- `GuideCommand`（debug 开测试屏）→`GuidebookCommand`
- 资源目录 `assets/wandscape/guide/`→`guidebook/`；文档 `guide:` 链接前缀→`guidebook:`
- lang `gui.wandscape.guide.*`→`gui.wandscape.guidebook.*`
- **保留**：物品注册 id `guide_book`（模型/贴图/配方/存档侵入，收益低——用户拍板）；key id `key.wandscape.guide`（仅改显示名）

## 归属判定（为何独立成域）

违反"items=纯物品容器（无系统内核）"——它是跨域聚合状态（认五域），不是任一域私有状态。塞某域或收 foundation 都不贴（foundation 天然不反向认识域），按语义独立成域最干净（与"归属看语义不看依赖方向"一致）。
